package com.rishab.scheduler.workers;

import java.time.Instant;

public record WorkerResponse(String workerId, Instant lastSeenAt) {
}
