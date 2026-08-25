package com.rishab.scheduler.jobs;

import java.util.Map;

// latencyPercentilesNote exists because spec §7 originally asked for p50/p95 execution latency
// here, read from "the injected Micrometer MeterRegistry" -- but execution happens in
// worker-service, a separate JVM with its own in-memory registry scheduler-service has no way to
// read. Faking those numbers from job_executions via SQL is explicitly what spec §7 says not to
// do (percentile_cont scans and degrades as history grows). Documenting the real limitation here
// is more honest than either faking the aggregation or silently dropping the requirement.
public record StatsResponse(Map<String, Long> countsByState, long completedInLastFiveMinutes, String latencyPercentilesNote) {
}
