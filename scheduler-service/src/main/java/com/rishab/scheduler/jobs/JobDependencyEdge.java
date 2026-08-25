package com.rishab.scheduler.jobs;

public record JobDependencyEdge(Long jobId, Long dependsOnId) {
}
