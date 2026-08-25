package com.rishab.scheduler.jobs;

import java.util.List;

// Spec §7: "Job detail including full attempt history" -- job_executions rows for a job are the
// audit trail the claim query and worker failure paths write to; the detail endpoint is the only
// place that history is ever read back out. dependsOn (Weekend 5) is included here rather than on
// the paginated list response to avoid an N+1 job_dependencies lookup per row when browsing a
// page of jobs that mostly have no dependencies at all.
public record JobDetailResponse(JobResponse job, List<JobExecutionResponse> executions, List<Long> dependsOn) {
}
