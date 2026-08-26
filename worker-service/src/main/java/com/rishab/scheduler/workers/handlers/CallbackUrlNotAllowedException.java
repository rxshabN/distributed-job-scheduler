package com.rishab.scheduler.workers.handlers;

import com.rishab.scheduler.workers.jobs.JobExecutionException;

public class CallbackUrlNotAllowedException extends JobExecutionException {

	public CallbackUrlNotAllowedException(String message) {
		super(message);
	}

	public CallbackUrlNotAllowedException(String message, Throwable cause) {
		super(message, cause);
	}
}
