package com.rishab.scheduler.workers.jobs;

import tools.jackson.databind.JsonNode;

public record JobContext(Long jobId, String jobType, JsonNode payload, int attempt, String workerId) {
}
