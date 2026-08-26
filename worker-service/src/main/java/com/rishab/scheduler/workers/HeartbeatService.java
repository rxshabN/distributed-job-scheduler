package com.rishab.scheduler.workers;

import com.rishab.scheduler.workers.jobs.JobClaimRepository;
import com.rishab.scheduler.workers.jobs.LeaseManager;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
		leaseManager.inFlightJobId().ifPresent(jobId -> jobClaimRepository.extendLease(
				jobId, workerIdentity.workerId(), workerProperties.leaseSeconds()));
	}

	private void beat() {
		String key = HEARTBEAT_KEY_PREFIX + workerIdentity.workerId();
		redisTemplate.opsForValue().set(key, Instant.now().toString(), Duration.ofSeconds(workerProperties.heartbeatTtlSeconds()));
	}
}
