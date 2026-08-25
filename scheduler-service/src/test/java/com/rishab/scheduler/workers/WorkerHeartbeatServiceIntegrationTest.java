package com.rishab.scheduler.workers;

import static org.assertj.core.api.Assertions.assertThat;

import com.rishab.scheduler.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class WorkerHeartbeatServiceIntegrationTest {

	@Autowired
	private WorkerHeartbeatService workerHeartbeatService;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Test
	void listsWorkersWithLiveHeartbeatKeys() {
		Instant lastSeen = Instant.parse("2026-01-01T00:00:00Z");
		redisTemplate.opsForValue().set("worker:heartbeat:worker-a", lastSeen.toString(), Duration.ofSeconds(30));

		assertThat(workerHeartbeatService.listAliveWorkers())
				.anySatisfy(worker -> {
					assertThat(worker.workerId()).isEqualTo("worker-a");
					assertThat(worker.lastSeenAt()).isEqualTo(lastSeen);
				});
		assertThat(workerHeartbeatService.aliveCount()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void doesNotListExpiredHeartbeats() {
		String key = "worker:heartbeat:worker-expired";
		redisTemplate.opsForValue().set(key, Instant.now().toString(), Duration.ofMillis(1));
		await(() -> Boolean.FALSE.equals(redisTemplate.hasKey(key)));

		assertThat(workerHeartbeatService.listAliveWorkers())
				.noneMatch(worker -> worker.workerId().equals("worker-expired"));
	}

	private static void await(java.util.function.Supplier<Boolean> condition) {
		for (int i = 0; i < 50 && !condition.get(); i++) {
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}
}
