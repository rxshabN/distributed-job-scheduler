package com.rishab.scheduler.workers;

import com.rishab.scheduler.workers.jobs.JobClaimRepository;
import com.rishab.scheduler.workers.jobs.LeaseManager;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Spec §5: "a worker registers a heartbeat key in Redis with a TTL at process startup, refreshed
// on a fixed interval independent of job activity -- not 'on claim'." Starting only on claim
// would leave an idle worker invisible to GET /api/v1/workers and workers_alive_count (spec §1)
// even though the process is perfectly healthy and would pick up the next job fine.
//
// The same refresh cycle also extends the lease of whatever job LeaseManager says is currently
// executing (spec §5: "extended alongside the heartbeat for long-running jobs") -- tying both to
// one scheduled method is a direct implementation of that sentence, not an independent design
// choice.
@Component
public class HeartbeatService {

	private static final String HEARTBEAT_KEY_PREFIX = "worker:heartbeat:";

	private final StringRedisTemplate redisTemplate;
	private final WorkerIdentity workerIdentity;
	private final WorkerProperties workerProperties;
	private final LeaseManager leaseManager;
	private final JobClaimRepository jobClaimRepository;

	public HeartbeatService(
			StringRedisTemplate redisTemplate,
			WorkerIdentity workerIdentity,
			WorkerProperties workerProperties,
			LeaseManager leaseManager,
			JobClaimRepository jobClaimRepository) {
		this.redisTemplate = redisTemplate;
		this.workerIdentity = workerIdentity;
		this.workerProperties = workerProperties;
		this.leaseManager = leaseManager;
		this.jobClaimRepository = jobClaimRepository;
	}

	@PostConstruct
	void writeInitialHeartbeat() {
		beat();
	}

	@Scheduled(fixedDelayString = "${worker.heartbeat-refresh-interval-ms}")
	void refresh() {
		beat();
		// workerId is passed so extendLease can verify this worker still owns the job -- see the
		// ownership guard there. LeaseManager only knows what this process last started executing,
		// which is not the same thing as what the database still says it owns.
		leaseManager.inFlightJobId().ifPresent(jobId -> jobClaimRepository.extendLease(
				jobId, workerIdentity.workerId(), workerProperties.leaseSeconds()));
	}

	private void beat() {
		String key = HEARTBEAT_KEY_PREFIX + workerIdentity.workerId();
		redisTemplate.opsForValue().set(key, Instant.now().toString(), Duration.ofSeconds(workerProperties.heartbeatTtlSeconds()));
	}
}
