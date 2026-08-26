package com.rishab.scheduler.jobs;

import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JobInsertion {

	private final JobRepository jobRepository;
	private final JobDependencyRepository jobDependencyRepository;

	JobInsertion(JobRepository jobRepository, JobDependencyRepository jobDependencyRepository) {
		this.jobRepository = jobRepository;
		this.jobDependencyRepository = jobDependencyRepository;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	Job insert(Job job, Set<Long> dependsOn) {
		Job saved = jobRepository.save(job);
		if (!dependsOn.isEmpty()) {
			jobDependencyRepository.insertEdges(saved.getId(), dependsOn);
		}
		return saved;
	}
}
