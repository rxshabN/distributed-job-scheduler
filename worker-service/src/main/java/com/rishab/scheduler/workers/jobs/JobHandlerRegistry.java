package com.rishab.scheduler.workers.jobs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

// Built once at startup from every JobHandler bean Spring's ApplicationContext knows about (spec
// §8: "discovered via Spring's ApplicationContext and registered in a map at startup"). Failing
// fast on a duplicate jobType here, rather than letting the second registration silently shadow
// the first, matters because the alternative failure mode is silent: the shadowed handler would
// just never run, with no error anywhere, for as long as both beans happened to coexist.
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
