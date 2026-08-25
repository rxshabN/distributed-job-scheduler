package com.rishab.scheduler.jobs;

import java.util.Set;

public class UnknownDependencyException extends RuntimeException {

	public UnknownDependencyException(Set<Long> missingJobIds) {
		super("dependsOn references job(s) that do not exist: " + missingJobIds);
	}
}
