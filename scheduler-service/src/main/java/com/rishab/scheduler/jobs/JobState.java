package com.rishab.scheduler.jobs;

public enum JobState {
	PENDING,
	RUNNING,
	SUCCEEDED,
	DEAD_LETTER,
	CANCELLED
}
