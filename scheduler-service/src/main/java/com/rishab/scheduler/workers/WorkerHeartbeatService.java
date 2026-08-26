package com.rishab.scheduler.workers;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class WorkerHeartbeatService {

	private static final String HEARTBEAT_KEY_PREFIX = "worker:heartbeat:";

	private final StringRedisTemplate redisTemplate;

	public WorkerHeartbeatService(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	public List<WorkerResponse> listAliveWorkers() {
		Set<String> keys = redisTemplate.keys(HEARTBEAT_KEY_PREFIX + "*");
		if (keys == null || keys.isEmpty()) {
			return List.of();
		}
		return keys.stream()
				.map(key -> {
					String workerId = key.substring(HEARTBEAT_KEY_PREFIX.length());
					String value = redisTemplate.opsForValue().get(key);
					Instant lastSeenAt = value != null ? Instant.parse(value) : null;
					return new WorkerResponse(workerId, lastSeenAt);
				})
				.sorted((a, b) -> a.workerId().compareTo(b.workerId()))
				.toList();
	}

	public long aliveCount() {
		Set<String> keys = redisTemplate.keys(HEARTBEAT_KEY_PREFIX + "*");
		return keys != null ? keys.size() : 0;
	}
}
