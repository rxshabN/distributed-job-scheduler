package com.rishab.scheduler.workers.jobs;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

// Tracks which single job this worker instance is actively executing right now, so
// HeartbeatService knows which job's lease_expires_at to extend alongside each heartbeat refresh
// (spec §5). JobPoller is single-threaded per instance (see JobPoller's own comment), so there is
// only ever at most one in-flight job per worker at a time -- an AtomicReference is enough, no
// need for a set or per-thread tracking.
//
// A batch of N claimed jobs only has its *currently executing* member's lease extended, not all N
// -- jobs #2..N sitting RUNNING while #1 executes keep whatever lease_expires_at the claim query
// set and are never extended while they wait their turn. If #1 runs long enough that #2..N's
// original lease expires before they start, that's the exact race spec §5 already documents:
// "a worker process can stay alive and heartbeating while the specific thread executing a job
// dies or hangs... there is no per-job liveness signal, only per-worker." The reaper's guard
// (heartbeat still alive) prevents them from being incorrectly reclaimed either way -- this is a
// known, accepted limitation of per-worker (not per-job) liveness, not a new bug.
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
