package com.rishab.scheduler.jobs;

import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// A separate bean, not a private method on JobService, so that REQUIRES_NEW actually takes
// effect: @Transactional is applied by a proxy around bean method calls, and a call from one
// method to another on the *same* instance skips that proxy entirely (self-invocation) -- the
// annotation would silently be ignored. Needs its own transaction because Postgres aborts the
// whole transaction on any statement failure; catching the idempotency_key unique-constraint
// violation and then querying for the existing row in that same transaction would just get
// "current transaction is aborted, commands ignored until end of transaction block" back.
@Component
class JobInsertion {

	private final JobRepository jobRepository;
	private final JobDependencyRepository jobDependencyRepository;

	JobInsertion(JobRepository jobRepository, JobDependencyRepository jobDependencyRepository) {
		this.jobRepository = jobRepository;
		this.jobDependencyRepository = jobDependencyRepository;
	}

	// dependsOn edges are inserted in the same REQUIRES_NEW transaction as the job row itself --
	// if the idempotency_key insert fails, the edges must not survive either, or they'd reference
	// a job id that was never actually created (the id from a failed IDENTITY insert is burned,
	// never reused, so a stray edge row would silently point at nothing).
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	Job insert(Job job, Set<Long> dependsOn) {
		Job saved = jobRepository.save(job);
		if (!dependsOn.isEmpty()) {
			jobDependencyRepository.insertEdges(saved.getId(), dependsOn);
		}
		return saved;
	}
}
