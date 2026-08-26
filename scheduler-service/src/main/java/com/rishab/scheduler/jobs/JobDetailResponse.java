package com.rishab.scheduler.jobs;

import java.util.List;

public record JobDetailResponse(JobResponse job, List<JobExecutionResponse> executions, List<Long> dependsOn) {
}
