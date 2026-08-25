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

// The claim transaction's outer loop (spec §4/§8 tied together): claim a batch, run each job
// through its handler, and record success/failure. Deliberately single-threaded per worker
// instance for Weekend 2 -- horizontal scaling (spec §1: "running N worker instances must not
// change correctness") comes from running more worker *processes* against the same claim query,
// not from adding an intra-process thread pool on top of it; a thread pool here would be
// complexity the spec never asked for.
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

	// Spec §10's job_queue_wait_duration. Recorded for the whole batch up front, against a single
	// claimedAt sampled once: jobs 2..N of a batch are already claimed and waiting on this
	// single-threaded loop, so timing them at the moment their turn comes would fold this worker's
	// own execution backlog into what is supposed to measure queue latency. The queue wait ended
	// when the row was claimed, not when the handler started.
	private void recordQueueWait(ClaimedJob job, Instant claimedAt) {
		Duration waited = Duration.between(job.nextRunAt(), claimedAt);
		// next_run_at comes from Postgres's clock and claimedAt from this JVM's; a worker whose
		// clock trails the database's by a few milliseconds would otherwise record a negative
		// duration, which Micrometer would happily fold into the sum and quietly corrupt the
		// percentiles. Clamping at zero is right for the metric's meaning too: a job cannot have
		// been claimed before it was due -- the claim query's own "next_run_at <= now()" predicate
		// guarantees it -- so any negative value here is measurement error, not signal.
		if (waited.isNegative()) {
			waited = Duration.ZERO;
		}
		meterRegistry.timer("job_queue_wait_duration", "jobType", job.jobType()).record(waited);
	}

	private void executeOne(ClaimedJob job) {
		// Spec §10: "structured JSON logging with jobId, workerId, attempt on every execution log
		// line" -- MDC keys are picked up by the structured console encoder configured in
		// application.yml (logging.structured.format.console), so every log line emitted while
		// this job is executing carries these fields without each call site formatting them by hand.
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
		// handleSuccess/handleFailure deliberately run outside this try block, once, rather than
		// being called from inside it: calling handleSuccess() from the try body would put its
		// own audit-insert-plus-update under the same catch(RuntimeException) below, so if THAT
		// ever failed (e.g. a transient DB error) it would be misreported as a handler failure
		// and re-enter handleFailure() a second time -- a second write attempt on a path that's
		// already failed once, from inside exception handling. Separating "did the handler
		// succeed" from "record the outcome" keeps each completion path a single, single-purpose
		// call.
		String failureMessage = null;
		Timer.Sample executionTimer = Timer.start(meterRegistry);
		try {
			handler.get().execute(context);
		} catch (JobExecutionException ex) {
			failureMessage = ex.getMessage();
		} catch (RuntimeException ex) {
			// A handler throwing something other than JobExecutionException is a bug in that
			// handler, not an expected job failure. It's still recorded and routed through the
			// normal failure path rather than left to propagate: an uncaught exception here would
			// abort this poll cycle and strand every other job already claimed in this same
			// batch as RUNNING with no way back to PENDING until the reaper's next sweep.
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
		// The audit row is written before the ownership-guarded state change, and unconditionally:
		// this worker really did execute this attempt, and job_executions is the record of what
		// ran, not of what counted. If the completion below turns out to be a no-op, the audit row
		// is the only surviving evidence that the work happened twice -- which is precisely the
		// at-least-once behaviour the README promises to make detectable after the fact.
		jobExecutionAuditRepository.recordAttempt(
				job.id(), job.attemptCount(), workerIdentity.workerId(), startedAt, Instant.now(), true, null);
		if (jobCompletionRepository.markSucceeded(job.id(), workerIdentity.workerId()) == 0) {
			recordStaleCompletion(job, "succeeded");
			return;
		}
		meterRegistry.counter("jobs_succeeded_total").increment();
		log.info("job {} succeeded on attempt {}", job.id(), job.attemptCount());
	}

	// A completion that found no row to update means this worker was reaped mid-execution and the
	// job now belongs to someone else (see JobCompletionRepository's guard). Nothing is retried and
	// nothing is escalated -- the new owner is running the job and will record its own outcome, so
	// the only correct action here is to drop this result on the floor.
	//
	// It is still counted and logged, because "we executed a job whose result we then had to throw
	// away" is the observable symptom of a false reclaim, and a false reclaim is otherwise
	// completely silent. jobs_reclaimed_total (scheduler-service) counts reclaims but cannot
	// distinguish a genuinely dead worker from a live one that was wrongly declared dead. A
	// non-zero rate here is that distinction, and it is the metric to watch after tuning
	// heartbeat-ttl-seconds or lease-seconds.
	//
	// The success/failure counters are deliberately NOT incremented on this path: the job's real
	// outcome is whatever its actual owner records, and counting it here would double-count every
	// falsely-reclaimed job.
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
