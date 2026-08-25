package com.rishab.scheduler.workers.handlers;

import com.rishab.scheduler.workers.jobs.JobExecutionException;

// Extends JobExecutionException rather than being a separate unchecked type, so a blocked callback
// travels the ordinary job-failure path: recorded in job_executions with its reason, retried under
// backoff, and eventually dead-lettered. That is the right classification -- from the system's
// point of view a refused destination is a job that cannot succeed, not a worker malfunction.
//
// The retries are knowingly wasted work (the policy will not change between attempts) but the
// alternative -- a distinct terminal outcome that skips straight to DEAD_LETTER -- would need a
// new state transition in V1's trigger and a new branch in JobPoller, to save at most
// max_attempts cheap DNS lookups. The error message is preserved on every attempt, so the reason
// is visible in the dashboard's attempt history either way.
public class CallbackUrlNotAllowedException extends JobExecutionException {

	public CallbackUrlNotAllowedException(String message) {
		super(message);
	}

	public CallbackUrlNotAllowedException(String message, Throwable cause) {
		super(message, cause);
	}
}
