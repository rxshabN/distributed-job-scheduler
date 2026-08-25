package com.rishab.scheduler.workers.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.rishab.scheduler.workers.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

// Spec §11 test 4 ("Retry exhaustion -- a handler that always fails; assert exactly maxAttempts
// executions then DEAD_LETTER") and test 5 ("Backoff timing -- assert next_run_at grows and stays
// within the jitter bounds"), driven through the real claim -> execute -> complete cycle rather
// than by calling the completion repository directly.
//
// Not @Transactional: JobPoller's claim, audit insert and completion update each run in their own
// transaction, and this test needs to observe the committed result of one full cycle before
// starting the next. A test-managed transaction would also hide exactly the thing under test --
// whether the *committed* row count matches maxAttempts.
//
// Between cycles the test pulls next_run_at back to now() instead of sleeping out the real
// backoff. That's the one piece of fidelity deliberately traded away: waiting out even a 1-second
// base delay across three attempts with full jitter makes the test slow and its runtime
// nondeterministic, and the delay itself is asserted separately in backoffDelayGrowsAndStaysWithinJitterBounds
// below (and exhaustively in BackoffPolicyTest). What matters here is the attempt accounting, not
// the wall-clock wait.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {
		"worker.poll-interval-ms=3600000",
		"worker.base-backoff-delay-millis=1000",
		"worker.max-backoff-delay-millis=300000",
		"spring.flyway.enabled=true"})
class RetryExhaustionIntegrationTest {

	private static final String KEY_PREFIX = "retry-exhaustion-test-";
	private static final long BASE_BACKOFF_MILLIS = 1000;
	private static final long MAX_BACKOFF_MILLIS = 300_000;

	@Autowired
	private JobPoller jobPoller;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void clearPreviousRun() {
		jdbcTemplate.update("DELETE FROM jobs WHERE idempotency_key LIKE ?", KEY_PREFIX + "%");
	}

	@Test
	void alwaysFailingHandlerProducesExactlyMaxAttemptsExecutionsThenDeadLetters() {
		int maxAttempts = 3;
		Long jobId = insertAlwaysFailingJob(maxAttempts);

		// Bounded "drive until terminal" rather than exactly maxAttempts iterations. A poll cycle
		// is not guaranteed to advance *this* job -- @Scheduled fires JobPoller once at context
		// startup regardless of poll-interval-ms, so a cycle can be in flight before the test body
		// begins and claim the fixture out from under the first iteration. Looping to a terminal
		// state keeps the assertion that matters ("exactly maxAttempts executions, then
		// DEAD_LETTER") exact, while the generous ceiling only guards against hanging: if the
		// retry accounting were broken the count assertions below still fail, they just fail on
		// the real number instead of on a mistimed loop.
		for (int cycle = 0; cycle < maxAttempts * 4 && !isTerminal(jobId); cycle++) {
			jobPoller.pollAndExecute();
			makeImmediatelyDueAgainIfStillPending(jobId);
		}

		assertThat(stateOf(jobId)).isEqualTo("DEAD_LETTER");
		assertThat(attemptCountOf(jobId)).isEqualTo(maxAttempts);

		List<Map<String, Object>> executions = jdbcTemplate.queryForList(
				"SELECT attempt, success, error_message FROM job_executions WHERE job_id = ? ORDER BY attempt", jobId);
		assertThat(executions).hasSize(maxAttempts);
		assertThat(executions).extracting(row -> row.get("attempt")).containsExactly(1, 2, 3);
		assertThat(executions).extracting(row -> row.get("success")).containsOnly(false);
		assertThat(executions).allSatisfy(row -> assertThat(row.get("error_message")).isNotNull());
	}

	@Test
	void aDeadLetteredJobIsNotClaimedAgainByAFurtherPollCycle() {
		Long jobId = insertAlwaysFailingJob(1);

		jobPoller.pollAndExecute();
		assertThat(stateOf(jobId)).isEqualTo("DEAD_LETTER");

		// The terminal state has to actually be terminal: the claim query filters on
		// state = 'PENDING', so a further cycle must neither re-run it nor add an audit row.
		// (This is also what keeps DEAD_LETTER -> RUNNING, which the V1 trigger forbids outright,
		// from ever being attempted.)
		jobPoller.pollAndExecute();

		assertThat(stateOf(jobId)).isEqualTo("DEAD_LETTER");
		assertThat(attemptCountOf(jobId)).isEqualTo(1);
		assertThat(auditRowCount(jobId)).isEqualTo(1);
	}

	@Test
	void backoffDelayGrowsAndStaysWithinJitterBoundsAcrossAttempts() {
		Long jobId = insertAlwaysFailingJob(5);

		// Full jitter picks uniformly from [0, cap], so any single attempt's delay can legally be
		// smaller than the previous one -- asserting a strictly increasing sequence would be
		// asserting a bug. What actually grows is the *ceiling*, so that is what is asserted per
		// attempt: delay <= min(2^attempt * base, max).
		for (int attempt = 1; attempt <= 4; attempt++) {
			Instant beforeCycle = Instant.now();
			jobPoller.pollAndExecute();

			assertThat(stateOf(jobId)).isEqualTo("PENDING");
			assertThat(attemptCountOf(jobId)).isEqualTo(attempt);

			Instant nextRunAt = nextRunAtOf(jobId);
			long observedDelayMillis = nextRunAt.toEpochMilli() - beforeCycle.toEpochMilli();
			long cap = Math.min((1L << attempt) * BASE_BACKOFF_MILLIS, MAX_BACKOFF_MILLIS);

			assertThat(observedDelayMillis)
					.as("attempt %d delay must fall inside the full-jitter window [0, %d]", attempt, cap)
					.isGreaterThanOrEqualTo(0)
					// Slack absorbs the handler's own execution time plus clock granularity between
					// the sample above and BackoffPolicy's own Instant.now() inside the poller.
					.isLessThanOrEqualTo(cap + 5_000);

			makeImmediatelyDueAgainIfStillPending(jobId);
		}
	}

	private boolean isTerminal(Long jobId) {
		String state = stateOf(jobId);
		return "DEAD_LETTER".equals(state) || "SUCCEEDED".equals(state) || "CANCELLED".equals(state);
	}

	private void makeImmediatelyDueAgainIfStillPending(Long jobId) {
		jdbcTemplate.update(
				"UPDATE jobs SET next_run_at = now() WHERE id = ? AND state = CAST('PENDING' AS job_state)", jobId);
	}

	private String stateOf(Long jobId) {
		return jdbcTemplate.queryForObject("SELECT state::text FROM jobs WHERE id = ?", String.class, jobId);
	}

	private int attemptCountOf(Long jobId) {
		return jdbcTemplate.queryForObject("SELECT attempt_count FROM jobs WHERE id = ?", Integer.class, jobId);
	}

	private Instant nextRunAtOf(Long jobId) {
		return jdbcTemplate.queryForObject("SELECT next_run_at FROM jobs WHERE id = ?", Timestamp.class, jobId).toInstant();
	}

	private int auditRowCount(Long jobId) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM job_executions WHERE job_id = ?", Integer.class, jobId);
	}

	private Long insertAlwaysFailingJob(int maxAttempts) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO jobs (idempotency_key, job_type, payload, state, priority, max_attempts, next_run_at, created_at, updated_at)
				VALUES (?, 'email-simulation', CAST(? AS jsonb), 'PENDING', 0, ?, ?, now(), now())
				RETURNING id
				""",
				Long.class,
				KEY_PREFIX + UUID.randomUUID(),
				objectMapper.writeValueAsString(Map.of("sleepMillis", 0, "failureProbability", 1.0)),
				maxAttempts,
				Timestamp.from(Instant.now().minusSeconds(5)));
	}
}
