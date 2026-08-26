package com.rishab.scheduler.workers.jobs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class JobHandlerRegistry {

	private final Map<String, JobHandler> handlersByType;

	public JobHandlerRegistry(List<JobHandler> handlers) {
		Map<String, JobHandler> byType = new HashMap<>();
		for (JobHandler handler : handlers) {
			JobHandler existing = byType.putIfAbsent(handler.jobType(), handler);
			if (existing != null) {
				throw new IllegalStateException("duplicate JobHandler for jobType '%s': %s and %s"
						.formatted(handler.jobType(), existing.getClass().getSimpleName(), handler.getClass().getSimpleName()));
			}
		}
		this.handlersByType = Map.copyOf(byType);
	}

	public Optional<JobHandler> find(String jobType) {
		return Optional.ofNullable(handlersByType.get(jobType));
	}
}
