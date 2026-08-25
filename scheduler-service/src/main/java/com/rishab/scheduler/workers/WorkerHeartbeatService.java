package com.rishab.scheduler.workers;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

// Reads the same worker:heartbeat:{workerId} keys worker-service's HeartbeatService writes
// (spec §5) and JobReaper checks -- this is purely a read-side view for GET /api/v1/workers and
// the workers_alive_count gauge, spec §7/§10. KEYS is O(N) and blocks Redis while it runs; SCAN
// would be the production-grade choice, but the number of workers in this system is never more
// than a handful, so the simpler call is the right tradeoff here rather than added complexity
// for a scale this project doesn't reach.
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
