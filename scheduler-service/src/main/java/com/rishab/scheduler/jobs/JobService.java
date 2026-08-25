package com.rishab.scheduler.jobs;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Set;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobService {

	// Postgres's default name for a single-column inline UNIQUE constraint: <table>_<column>_key.
	private static final String IDEMPOTENCY_KEY_CONSTRAINT = "jobs_idempotency_key_key";

	// A not-yet-inserted job has no real id yet, but DependencyCycleDetector needs some node
	// identity to build the graph with. BIGSERIAL never produces a negative id, so this can never
	// collide with a real job.
	private static final Long PENDING_SUBMISSION_SENTINEL_ID = -1L;

	private final JobRepository jobRepository;
	private final JobExecutionRepository jobExecutionRepository;
	private final JobInsertion jobInsertion;
	private final JobTransitionRepository jobTransitionRepository;
	private final JobDependencyRepository jobDependencyRepository;
	private final DependencyCycleDetector dependencyCycleDetector;
	private final MeterRegistry meterRegistry;

	public JobService(JobRepository jobRepository, JobExecutionRepository jobExecutionRepository,
			JobInsertion jobInsertion, JobTransitionRepository jobTransitionRepository,
			JobDependencyRepository jobDependencyRepository, DependencyCycleDetector dependencyCycleDetector,
			MeterRegistry meterRegistry) {
		this.jobRepository = jobRepository;
		this.jobExecutionRepository = jobExecutionRepository;
		this.jobInsertion = jobInsertion;
		this.jobTransitionRepository = jobTransitionRepository;
		this.jobDependencyRepository = jobDependencyRepository;
		this.dependencyCycleDetector = dependencyCycleDetector;
		this.meterRegistry = meterRegistry;
	}

	public JobSubmissionResult submit(JobSubmissionRequest request) {
		Set<Long> dependsOn = request.dependsOnOrEmpty();
		if (!dependsOn.isEmpty()) {
			validateDependencies(dependsOn);
		}

		Job job = new Job(
				request.idempotencyKey(),
				request.jobType(),
				request.payload(),
				request.priorityOrDefault(),
				request.maxAttemptsOrDefault(),
				request.runAtOrDefault());

		try {
			Job inserted = jobInsertion.insert(job, dependsOn);
			// Only the genuinely-new-row path counts as a submission -- resubmitting a known
			// idempotency_key isn't a second job entering the system, spec §7.
			meterRegistry.counter("jobs_submitted_total").increment();
			return new JobSubmissionResult(inserted, true);
		} catch (DataIntegrityViolationException ex) {
			if (!isIdempotencyKeyConflict(ex)) {
				throw ex;
			}
			// The insert's own transaction already rolled back (JobInsertion.insert is
			// REQUIRES_NEW), so this lookup runs cleanly in a fresh one rather than inheriting an
			// aborted transaction.
			Job existing = jobRepository.findByIdempotencyKey(request.idempotencyKey()).orElseThrow(() -> ex);
			return new JobSubmissionResult(existing, false);
		}
	}

	private void validateDependencies(Set<Long> dependsOn) {
		Set<Long> missing = jobDependencyRepository.findMissingJobIds(dependsOn);
		if (!missing.isEmpty()) {
			throw new UnknownDependencyException(missing);
		}
		List<JobDependencyEdge> existingEdges = jobDependencyRepository.findAllEdges();
		dependencyCycleDetector.findCycle(existingEdges, PENDING_SUBMISSION_SENTINEL_ID, dependsOn)
				.ifPresent(cycle -> {
					throw new DependencyCycleException(cycle);
				});
	}

	@Transactional
	public Job cancel(Long id) {
		if (jobTransitionRepository.cancel(id) == 1) {
			return jobRepository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
		}
		JobState currentState = jobTransitionRepository.findState(id).orElseThrow(() -> new JobNotFoundException(id));
		throw new IllegalJobStateTransitionException(id, currentState, "cancel", JobState.PENDING);
	}

	@Transactional
	public Job retry(Long id) {
		if (jobTransitionRepository.retry(id) == 1) {
			return jobRepository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
		}
		JobState currentState = jobTransitionRepository.findState(id).orElseThrow(() -> new JobNotFoundException(id));
		throw new IllegalJobStateTransitionException(id, currentState, "retry", JobState.DEAD_LETTER);
	}

	private static boolean isIdempotencyKeyConflict(DataIntegrityViolationException ex) {
		return ex.getCause() instanceof ConstraintViolationException cve
				&& IDEMPOTENCY_KEY_CONSTRAINT.equals(cve.getConstraintName());
	}

	@Transactional(readOnly = true)
	public JobDetailResponse getDetail(Long id) {
		Job job = jobRepository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
		List<JobExecution> executions = jobExecutionRepository.findByJobIdOrderByAttemptAsc(id);
		List<Long> dependsOn = jobDependencyRepository.findDependencyIds(id);
		return JobMapper.toDetailResponse(job, executions, dependsOn);
	}

	@Transactional(readOnly = true)
	public Page<Job> list(JobState state, String jobType, Pageable pageable) {
		return jobRepository.findAll(JobSpecifications.matching(state, jobType), pageable);
	}
}
