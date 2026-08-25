package com.rishab.scheduler.jobs;

import java.util.List;

final class JobMapper {

	private JobMapper() {
	}

	static JobResponse toResponse(Job job) {
		return new JobResponse(
				job.getId(),
				job.getIdempotencyKey(),
				job.getJobType(),
				job.getPayload(),
				job.getState(),
				job.getPriority(),
				job.getAttemptCount(),
				job.getMaxAttempts(),
				job.getNextRunAt(),
				job.getClaimedBy(),
				job.getLeaseExpiresAt(),
				job.getCreatedAt(),
				job.getUpdatedAt());
	}

	static JobExecutionResponse toResponse(JobExecution execution) {
		return new JobExecutionResponse(
				execution.getId(),
				execution.getAttempt(),
				execution.getWorkerId(),
				execution.getStartedAt(),
				execution.getFinishedAt(),
				execution.getSuccess(),
				execution.getErrorMessage());
	}

	static JobDetailResponse toDetailResponse(Job job, List<JobExecution> executions, List<Long> dependsOn) {
		return new JobDetailResponse(toResponse(job), executions.stream().map(JobMapper::toResponse).toList(), dependsOn);
	}
}
