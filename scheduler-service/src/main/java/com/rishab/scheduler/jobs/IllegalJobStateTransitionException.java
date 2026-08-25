package com.rishab.scheduler.jobs;

public class IllegalJobStateTransitionException extends RuntimeException {

	public IllegalJobStateTransitionException(Long jobId, JobState actual, String action, JobState required) {
		super("cannot %s job %d: expected state %s but was %s".formatted(action, jobId, required, actual));
	}
}
