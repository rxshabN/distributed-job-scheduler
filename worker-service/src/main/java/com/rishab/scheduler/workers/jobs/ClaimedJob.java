package com.rishab.scheduler.workers.jobs;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

// attemptCount here is post-claim -- the claim query (spec §4) increments attempt_count as part
// of the same UPDATE that sets state = RUNNING, so this is already "the attempt currently in
// progress", not "attempts so far before this one".
//
// nextRunAt is pre-claim: the moment this job became eligible to run. It is carried through
// purely so JobPoller can record job_queue_wait_duration (spec §10) as "how long was this job
// due before anyone picked it up" -- deliberately measured from next_run_at rather than
// created_at, because a job submitted with runAt an hour out, or one sitting in retry backoff,
// is not waiting on the system during that time and counting it as queue latency would make the
// metric say the workers are behind when they are idle.
public record ClaimedJob(
		Long id,
		String jobType,
		JsonNode payload,
		int attemptCount,
		int maxAttempts,
		int priority,
		Instant nextRunAt) {
}
