package com.rishab.scheduler.jobs;

import java.util.List;
import java.util.stream.Collectors;

public class DependencyCycleException extends RuntimeException {

	public DependencyCycleException(List<Long> cycle) {
		super("dependsOn would create a cycle: " + cycle.stream().map(String::valueOf).collect(Collectors.joining(" -> ")));
	}
}
