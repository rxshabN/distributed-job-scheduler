package com.rishab.scheduler.jobs;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

final class JobSpecifications {

	private JobSpecifications() {
	}

	static Specification<Job> matching(JobState state, String jobType) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (state != null) {
				predicates.add(cb.equal(root.get("state"), state));
			}
			if (jobType != null && !jobType.isBlank()) {
				predicates.add(cb.equal(root.get("jobType"), jobType));
			}
			return cb.and(predicates.toArray(new Predicate[0]));
		};
	}
}
