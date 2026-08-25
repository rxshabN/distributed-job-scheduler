package com.rishab.scheduler.workers.jobs;

// Checked: a JobHandler failing is an expected, routine outcome (spec §6's whole retry/backoff
// path exists because of it), not a programming error -- callers are required to handle it, not
// free to let it propagate as an unchecked surprise.
public class JobExecutionException extends Exception {

	public JobExecutionException(String message) {
		super(message);
	}

	public JobExecutionException(String message, Throwable cause) {
		super(message, cause);
	}
}
