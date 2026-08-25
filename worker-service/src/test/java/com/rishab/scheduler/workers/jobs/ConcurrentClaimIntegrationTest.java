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

// Spec §11 test 1: "10 threads claim from a pool of 100 jobs; assert every job claimed exactly
// once and no thread blocks."
//
// Deliberately NOT @Transactional, unlike the other repository integration tests in this package.
// A test-managed rollback-only transaction would confine every thread's work to one connection's
// uncommitted snapshot, which is precisely the thing FOR UPDATE SKIP LOCKED operates across --
// the claim would degenerate into a single-connection exercise and prove nothing about
// concurrency. Each worker thread therefore commits for real, and cleanup happens by
// idempotency-key prefix in @BeforeEach instead of by rollback.
//
// "No thread blocks" is asserted structurally rather than by timing a stopwatch (which would be
// flaky on a loaded CI runner): with plain FOR UPDATE, 9 of 10 threads would queue behind the
// first on the same candidate rows and the pool would drain in near-serial rounds. The assertion
// that every thread returns before the executor's timeout, combined with the exactly-once result,
// is what SKIP LOCKED buys -- a blocked thread would either time out here or produce a duplicate.
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
		// ON DELETE CASCADE on job_executions.job_id takes the audit rows with it.
		jdbcTemplate.update("DELETE FROM jobs WHERE idempotency_key LIKE ?", KEY_PREFIX + "%");
	}

	@Test
	void claimsEachJobExactlyOnceUnderConcurrency() throws Exception {
		Set<Long> insertedIds = insertPendingJobs(JOB_COUNT);

		List<Long> allClaimedIds = drainPoolWithConcurrentWorkers();

		// Exactly-once has two halves and both have to hold: nothing claimed twice (no duplicate
		// in the combined result) and nothing left behind (the union covers the whole pool).
		assertThat(allClaimedIds).hasSize(JOB_COUNT);
		assertThat(allClaimedIds).doesNotHaveDuplicates();
		assertThat(Set.copyOf(allClaimedIds)).isEqualTo(insertedIds);
	}

	@Test
	void everyConcurrentlyClaimedJobIsLeftRunningOnExactlyItsFirstAttempt() throws Exception {
		insertPendingJobs(JOB_COUNT);

		drainPoolWithConcurrentWorkers();

		// attempt_count is incremented inside the same UPDATE that sets RUNNING, so a job claimed
		// twice would show attempt_count = 2 here even if both claims somehow returned the same
		// id to different threads without the id-level duplicate check above catching it.
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

		// job_executions' UNIQUE (job_id, attempt) is the database-level proof the roadmap's load
		// test leans on: if two workers had claimed the same job on the same attempt, one of these
		// inserts would raise a constraint violation instead of completing. Writing one audit row
		// per claim turns "exactly once" from an in-memory assertion into one Postgres enforces.
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

	// Each thread loops claim() until the shared pool is drained, rather than claiming once and
	// stopping: with batchSize 5 and 100 jobs, a single round of 10 threads could only ever take
	// 50: the remaining 50 would be untested. Looping until empty is what actually forces the
	// threads to contend repeatedly over a shrinking candidate set, which is where a claim query
	// missing SKIP LOCKED would show up.
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
							// Another thread holds the last rows' locks but hasn't committed yet;
							// the pool isn't drained, so yield and retry rather than exiting and
							// leaving the tail unclaimed.
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
				// A thread that blocked instead of skipping locked rows would still be waiting
				// here; the timeout turns "no thread blocks" into a failing test rather than a
				// hang.
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
