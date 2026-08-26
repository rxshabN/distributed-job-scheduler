package com.rishab.scheduler.workers.jobs;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class LeaseManager {

	private final AtomicReference<Long> inFlightJobId = new AtomicReference<>();

	public void startTracking(Long jobId) {
		inFlightJobId.set(jobId);
	}

	public void stopTracking() {
		inFlightJobId.set(null);
	}

	public Optional<Long> inFlightJobId() {
		return Optional.ofNullable(inFlightJobId.get());
	}
}
