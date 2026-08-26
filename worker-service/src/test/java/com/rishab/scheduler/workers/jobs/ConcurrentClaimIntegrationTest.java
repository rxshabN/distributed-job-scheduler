package com.rishab.scheduler.workers.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.rishab.scheduler.workers.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {"worker.poll-interval-ms=3600000", "spring.flyway.enabled=true"})
class ConcurrentClaimIntegrationTest {

	private static final String KEY_PREFIX = "concurrent-claim-test-";
	private static final int JOB_COUNT = 100;
	private static final int THREAD_COUNT = 10;
	private static final int BATCH_SIZE = 5;

	@Autowired
	private JobClaimRepository jobClaimRepository;

	@Autowired
	private JobExecutionAuditRepository jobExecutionAuditRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void clearPreviousRun() {
		jdbcTemplate.update("DELETE FROM jobs WHERE idempotency_key LIKE ?", KEY_PREFIX + "%");
	}

	@Test
	void claimsEachJobExactlyOnceUnderConcurrency() throws Exception {
		Set<Long> insertedIds = insertPendingJobs(JOB_COUNT);

		List<Long> allClaimedIds = drainPoolWithConcurrentWorkers();

		assertThat(allClaimedIds).hasSize(JOB_COUNT);
		assertThat(allClaimedIds).doesNotHaveDuplicates();
		assertThat(Set.copyOf(allClaimedIds)).isEqualTo(insertedIds);
	}

	@Test
	void everyConcurrentlyClaimedJobIsLeftRunningOnExactlyItsFirstAttempt() throws Exception {
		insertPendingJobs(JOB_COUNT);

		drainPoolWithConcurrentWorkers();

		List<Integer> attemptCounts = jdbcTemplate.queryForList(
				"SELECT attempt_count FROM jobs WHERE idempotency_key LIKE ?", Integer.class, KEY_PREFIX + "%");
		assertThat(attemptCounts).hasSize(JOB_COUNT).containsOnly(1);

		List<String> states = jdbcTemplate.queryForList(
				"SELECT state::text FROM jobs WHERE idempotency_key LIKE ?", String.class, KEY_PREFIX + "%");
		assertThat(states).containsOnly("RUNNING");

		List<String> claimants = jdbcTemplate.queryForList(
				"SELECT DISTINCT claimed_by FROM jobs WHERE idempotency_key LIKE ?", String.class, KEY_PREFIX + "%");
		assertThat(claimants).hasSizeGreaterThan(1);
	}

	@Test
	void concurrentClaimsSurviveTheJobExecutionsUniquenessConstraint() throws Exception {
		insertPendingJobs(JOB_COUNT);

		List<Long> allClaimedIds = drainPoolWithConcurrentWorkers();

		for (Long jobId : allClaimedIds) {
			jobExecutionAuditRepository.recordAttempt(
					jobId, 1, "assert-worker", Instant.now(), Instant.now(), true, null);
		}

		Integer auditRows = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM job_executions e
				JOIN jobs j ON j.id = e.job_id
				WHERE j.idempotency_key LIKE ?
				""", Integer.class, KEY_PREFIX + "%");
		assertThat(auditRows).isEqualTo(JOB_COUNT);
	}

	private List<Long> drainPoolWithConcurrentWorkers() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
		CountDownLatch startGate = new CountDownLatch(1);
		AtomicInteger remaining = new AtomicInteger(JOB_COUNT);
		try {
			List<Callable<List<Long>>> tasks = new ArrayList<>();
			for (int i = 0; i < THREAD_COUNT; i++) {
				String workerId = "concurrent-worker-" + i;
				tasks.add(() -> {
					startGate.await();
					List<Long> mine = new ArrayList<>();
					while (remaining.get() > 0) {
						List<ClaimedJob> batch = jobClaimRepository.claim(workerId, BATCH_SIZE, 30);
						if (batch.isEmpty()) {
							Thread.yield();
							continue;
						}
						batch.forEach(job -> mine.add(job.id()));
						remaining.addAndGet(-batch.size());
					}
					return mine;
				});
			}

			List<Future<List<Long>>> futures = new ArrayList<>();
			for (Callable<List<Long>> task : tasks) {
				futures.add(executor.submit(task));
			}
			startGate.countDown();

			List<Long> allClaimedIds = new ArrayList<>();
			for (Future<List<Long>> future : futures) {
				allClaimedIds.addAll(future.get(60, TimeUnit.SECONDS));
			}
			return allClaimedIds;
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}
	}

	private Set<Long> insertPendingJobs(int count) {
		Set<Long> ids = new java.util.HashSet<>();
		Timestamp due = Timestamp.from(Instant.now().minus(Duration.ofSeconds(5)));
		for (int i = 0; i < count; i++) {
			ids.add(jdbcTemplate.queryForObject("""
					INSERT INTO jobs (idempotency_key, job_type, payload, state, priority, max_attempts, next_run_at, created_at, updated_at)
					VALUES (?, 'email-simulation', CAST(? AS jsonb), 'PENDING', 0, 5, ?, now(), now())
					RETURNING id
					""",
					Long.class,
					KEY_PREFIX + UUID.randomUUID(),
					objectMapper.createObjectNode().toString(),
					due));
		}
		assertThat(ids).hasSize(count);
		return ids;
	}
}
