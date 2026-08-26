package com.rishab.scheduler.workers.jobs;

import com.rishab.scheduler.workers.WorkerIdentity;
import com.rishab.scheduler.workers.WorkerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class JobPoller {

	private static final Logger log = LoggerFactory.getLogger(JobPoller.class);

	private final JobClaimRepository jobClaimRepository;
	private final JobCompletionRepository jobCompletionRepository;
	private final JobExecutionAuditRepository jobExecutionAuditRepository;
	private final JobHandlerRegistry jobHandlerRegistry;
	private final BackoffPolicy backoffPolicy;
	private final WorkerIdentity workerIdentity;
	private final WorkerProperties workerProperties;
	private final LeaseManager leaseManager;
	private final MeterRegistry meterRegistry;

	public JobPoller(
			JobClaimRepository jobClaimRepository,
			JobCompletionRepository jobCompletionRepository,
			JobExecutionAuditRepository jobExecutionAuditRepository,
			JobHandlerRegistry jobHandlerRegistry,
			BackoffPolicy backoffPolicy,
			WorkerIdentity workerIdentity,
			WorkerProperties workerProperties,
			LeaseManager leaseManager,
			MeterRegistry meterRegistry) {
		this.jobClaimRepository = jobClaimRepository;
		this.jobCompletionRepository = jobCompletionRepository;
		this.jobExecutionAuditRepository = jobExecutionAuditRepository;
		this.jobHandlerRegistry = jobHandlerRegistry;
		this.backoffPolicy = backoffPolicy;
		this.workerIdentity = workerIdentity;
		this.workerProperties = workerProperties;
		this.leaseManager = leaseManager;
		this.meterRegistry = meterRegistry;
	}

	@Scheduled(fixedDelayString = "${worker.poll-interval-ms}")
	public void pollAndExecute() {
		List<ClaimedJob> claimed = jobClaimRepository.claim(
				workerIdentity.workerId(), workerProperties.batchSize(), workerProperties.leaseSeconds());
		Instant claimedAt = Instant.now();
		for (ClaimedJob job : claimed) {
			recordQueueWait(job, claimedAt);
		}
		for (ClaimedJob job : claimed) {
			executeOne(job);
		}
	}

	private void recordQueueWait(ClaimedJob job, Instant claimedAt) {
		Duration waited = Duration.between(job.nextRunAt(), claimedAt);
		if (waited.isNegative()) {
			waited = Duration.ZERO;
		}
		meterRegistry.timer("job_queue_wait_duration", "jobType", job.jobType()).record(waited);
	}

	private void executeOne(ClaimedJob job) {
		MDC.put("jobId", String.valueOf(job.id()));
		MDC.put("workerId", workerIdentity.workerId());
		MDC.put("attempt", String.valueOf(job.attemptCount()));
		try {
			executeOneWithLoggingContext(job);
		} finally {
			MDC.remove("jobId");
			MDC.remove("workerId");
			MDC.remove("attempt");
		}
	}

	private void executeOneWithLoggingContext(ClaimedJob job) {
		Instant startedAt = Instant.now();
		Optional<JobHandler> handler = jobHandlerRegistry.find(job.jobType());
		if (handler.isEmpty()) {
			handleFailure(job, startedAt, "no handler registered for jobType '" + job.jobType() + "'");
			return;
		}

		JobContext context = new JobContext(job.id(), job.jobType(), job.payload(), job.attemptCount(), workerIdentity.workerId());
		leaseManager.startTracking(job.id());
		String failureMessage = null;
		Timer.Sample executionTimer = Timer.start(meterRegistry);
		try {
			handler.get().execute(context);
		} catch (JobExecutionException ex) {
			failureMessage = ex.getMessage();
		} catch (RuntimeException ex) {
			log.error("handler for jobType '{}' threw an unchecked exception on job {}", job.jobType(), job.id(), ex);
			failureMessage = "unexpected exception: " + ex;
		} finally {
			executionTimer.stop(meterRegistry.timer("job_execution_duration", "jobType", job.jobType()));
			leaseManager.stopTracking();
		}

		if (failureMessage == null) {
			handleSuccess(job, startedAt);
		} else {
			handleFailure(job, startedAt, failureMessage);
		}
	}

	private void handleSuccess(ClaimedJob job, Instant startedAt) {
		jobExecutionAuditRepository.recordAttempt(
				job.id(), job.attemptCount(), workerIdentity.workerId(), startedAt, Instant.now(), true, null);
		if (jobCompletionRepository.markSucceeded(job.id(), workerIdentity.workerId()) == 0) {
			recordStaleCompletion(job, "succeeded");
			return;
		}
		meterRegistry.counter("jobs_succeeded_total").increment();
		log.info("job {} succeeded on attempt {}", job.id(), job.attemptCount());
	}

	private void recordStaleCompletion(ClaimedJob job, String discardedOutcome) {
		meterRegistry.counter("jobs_stale_completions_total", "outcome", discardedOutcome).increment();
		log.warn("discarding '{}' outcome for job {} attempt {}: this worker no longer owns it "
						+ "(reclaimed mid-execution and re-claimed elsewhere)",
				discardedOutcome, job.id(), job.attemptCount());
	}

	private void handleFailure(ClaimedJob job, Instant startedAt, String errorMessage) {
		jobExecutionAuditRepository.recordAttempt(
				job.id(), job.attemptCount(), workerIdentity.workerId(), startedAt, Instant.now(), false, errorMessage);
		if (job.attemptCount() < job.maxAttempts()) {
			Instant nextRunAt = backoffPolicy.nextRunAt(job.attemptCount(), Instant.now());
			if (jobCompletionRepository.rescheduleWithBackoff(job.id(), workerIdentity.workerId(), nextRunAt) == 0) {
				recordStaleCompletion(job, "rescheduled");
				return;
			}
			meterRegistry.counter("jobs_failed_total").increment();
			log.warn("job {} failed on attempt {}/{}, rescheduled for {}: {}",
					job.id(), job.attemptCount(), job.maxAttempts(), nextRunAt, errorMessage);
		} else {
			if (jobCompletionRepository.markDeadLettered(job.id(), workerIdentity.workerId()) == 0) {
				recordStaleCompletion(job, "dead-lettered");
				return;
			}
			meterRegistry.counter("jobs_failed_total").increment();
			meterRegistry.counter("jobs_dead_lettered_total").increment();
			log.error("job {} dead-lettered after {}/{} attempts: {}",
					job.id(), job.attemptCount(), job.maxAttempts(), errorMessage);
		}
	}
}
