package com.rishab.scheduler.workers.jobs;

// Signature is exactly spec §8's -- implementations are discovered as Spring beans and registered
// by jobType() in JobHandlerRegistry, not looked up by class name or wired individually.
public interface JobHandler {

	String jobType();

	void execute(JobContext ctx) throws JobExecutionException;
}
