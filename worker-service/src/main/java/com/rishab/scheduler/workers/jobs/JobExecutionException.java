package com.rishab.scheduler.workers.jobs;

public class JobExecutionException extends Exception {

	public JobExecutionException(String message) {
		super(message);
	}

	public JobExecutionException(String message, Throwable cause) {
		super(message, cause);
	}
}
