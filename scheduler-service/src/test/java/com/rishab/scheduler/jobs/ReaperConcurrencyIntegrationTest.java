package com.rishab.scheduler.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.rishab.scheduler.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReaperConcurrencyIntegrationTest {

	private static final String KEY_PREFIX = "reaper-concurrency-test-";
	private static final int ORPHAN_COUNT = 50;
	private static final int REAPER_COUNT = 4;

	@Autowired
	private JobReaper jobReaper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MeterRegistry meterRegistry;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void clearPreviousRun() {
		jdbcTemplate.update("DELETE FROM jobs WHERE idempotency_key LIKE ?", KEY_PREFIX + "%");
	}

	@Test
	void concurrentReapersRequeueEachOrphanExactlyOnce() throws Exception {
		List<Long> orphanIds = insertOrphanedRunningJobs(ORPHAN_COUNT);

		double reclaimedBefore = reclaimedCounterValue();

		runReapersConcurrently();

		assertThat(reclaimedCounterValue() - reclaimedBefore).isEqualTo(ORPHAN_COUNT);

		List<String> states = jdbcTemplate.queryForList(
				"SELECT state::text FROM jobs WHERE idempotency_key LIKE ?", String.class, KEY_PREFIX + "%");
		assertThat(states).hasSize(ORPHAN_COUNT).containsOnly("PENDING");
		assertThat(orphanIds).allSatisfy(id -> assertThat(claimedByOf(id)).isNull());
	}

	@Test
	void concurrentReapersDoNotInflateAttemptCounts() throws Exception {
		insertOrphanedRunningJobs(ORPHAN_COUNT);

		runReapersConcurrently();

		List<Integer> attemptCounts = jdbcTemplate.queryForList(
				"SELECT attempt_count FROM jobs WHERE idempotency_key LIKE ?", Integer.class, KEY_PREFIX + "%");
		assertThat(attemptCounts).hasSize(ORPHAN_COUNT).containsOnly(1);
	}

	private void runReapersConcurrently() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(REAPER_COUNT);
		CountDownLatch startGate = new CountDownLatch(1);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < REAPER_COUNT; i++) {
				Callable<Void> task = () -> {
					startGate.await();
					jobReaper.reclaimOrphans();
					return null;
				};
				futures.add(executor.submit(task));
			}
			startGate.countDown();
			for (Future<?> future : futures) {
				future.get(60, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}
	}

	private double reclaimedCounterValue() {
		return meterRegistry.counter("jobs_reclaimed_total").count();
	}

	private String claimedByOf(Long jobId) {
		return jdbcTemplate.queryForObject("SELECT claimed_by FROM jobs WHERE id = ?", String.class, jobId);
	}

	private List<Long> insertOrphanedRunningJobs(int count) {
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			Long jobId = jdbcTemplate.queryForObject("""
					INSERT INTO jobs (idempotency_key, job_type, payload, state, priority, attempt_count, max_attempts, next_run_at, created_at, updated_at)
					VALUES (?, 'email-simulation', CAST(? AS jsonb), 'PENDING', 0, 1, 5, now(), now(), now())
					RETURNING id
					""",
					Long.class, KEY_PREFIX + UUID.randomUUID(), objectMapper.createObjectNode().toString());
			jdbcTemplate.update("""
					UPDATE jobs SET state = CAST('RUNNING' AS job_state), claimed_by = ?, lease_expires_at = ?
					WHERE id = ?
					""",
					"crashed-worker-" + i, Timestamp.from(Instant.now().minusSeconds(30)), jobId);
			ids.add(jobId);
		}
		return ids;
	}
}
