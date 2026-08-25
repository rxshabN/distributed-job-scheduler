package com.rishab.scheduler.jobs;

public class JobNotFoundException extends RuntimeException {

	public JobNotFoundException(Long id) {
		super("job not found: " + id);
	}
}
