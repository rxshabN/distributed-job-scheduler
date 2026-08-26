package com.rishab.scheduler.workers.jobs;

public interface JobHandler {

	String jobType();

	void execute(JobContext ctx) throws JobExecutionException;
}
