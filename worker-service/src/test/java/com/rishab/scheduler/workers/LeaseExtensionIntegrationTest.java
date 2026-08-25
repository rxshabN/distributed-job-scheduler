package com.rishab.scheduler.workers;

import static org.assertj.core.api.Assertions.assertThat;

import com.rishab.scheduler.workers.jobs.ClaimedJob;
import com.rishab.scheduler.workers.jobs.JobClaimRepository;
import com.rishab.scheduler.workers.jobs.LeaseManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

// Spec §11 test 6: "a job running longer than the base lease is not reclaimed while its worker
// heartbeats." Both halves of that sentence are checked -- the Redis heartbeat key stays present,
// and lease_expires_at is actually pushed forward past the original lease -- because either one
// alone would let the job be reclaimed: JobReaper's guard is (lease expired AND no heartbeat).
//
// The reaper itself lives in scheduler-service and is not on this service's classpath, so the
// last assertion replicates its candidate-selection predicate verbatim rather than invoking it.
// That is a real seam: if JobReaper's WHERE clause ever changes, this copy has to change with it.
// It is still worth having here, because the mechanism being tested -- HeartbeatService extending
// the lease of whatever LeaseManager says is in flight -- is entirely worker-side, and moving the
// test to scheduler-service would mean reimplementing the heartbeat/lease half instead.
//
// worker.lease-seconds is pushed down to 2 so the extension the refresh grants is visibly short,
// making "the lease moved forward" a real assertion rather than one satisfied by the production
// default's 30-second cushion. poll-interval-ms and heartbeat-refresh-interval-ms are pushed out
// so neither scheduled task can fire mid-test and mutate LeaseManager or the lease underneath an
// assertion.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {
		"worker.poll-interval-ms=3600000",
		"worker.heartbeat-refresh-interval-ms=3600000",
		"worker.lease-seconds=2",
		"worker.heartbeat-ttl-seconds=15",
		"spring.flyway.enabled=true"})
class LeaseExtensionIntegrationTest {

	private static final String KEY_PREFIX = "lease-extension-test-";

	@Autowired
	private JobClaimRepository jobClaimRepository;

	@Autowired
	private HeartbeatService heartbeatService;

	@Autowired
	private LeaseManager leaseManager;

	@Autowired
	private WorkerIdentity workerIdentity;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void clearPreviousRun() {
		jdbcTemplate.update("DELETE FROM jobs WHERE idempotency_key LIKE ?", KEY_PREFIX + "%");
		leaseManager.stopTracking();
	}

	@Test
	void heartbeatRefreshExtendsTheLeaseOfTheInFlightJobPastItsOriginalExpiry() {
		Long jobId = claimFreshJobAsThisWorker();
		// The lease is driven into the past with SQL rather than by sleeping out a short
		// worker.lease-seconds. Sleeping would make the test both slower and timing-marginal, and
		// it would test the clock rather than the mechanism: what the extension actually has to do
		// is move an already-lapsed lease_expires_at forward, and setting it directly states that
		// precondition instead of hoping the sleep outran it.
		Instant lapsedLease = expireLease(jobId);
		assertThat(lapsedLease).isBefore(Instant.now());

		trackAsInFlight(jobId);
		heartbeatService.refresh();

		Instant extendedLease = leaseExpiresAtOf(jobId);
		assertThat(extendedLease).isAfter(lapsedLease);
		assertThat(extendedLease).isAfter(Instant.now());
		assertThat(stateOf(jobId)).isEqualTo("RUNNING");
	}

	@Test
	void aStillHeartbeatingWorkersLongRunningJobIsNotAReaperCandidate() {
		Long jobId = claimFreshJobAsThisWorker();
		expireLease(jobId);

		trackAsInFlight(jobId);
		heartbeatService.refresh();

		// JobReaper (scheduler-service) reclaims only when BOTH halves hold: the lease has lapsed
		// and the claiming worker has no live heartbeat key. Assert neither half does.
		assertThat(reaperCandidateIdsWithExpiredLease()).doesNotContain(jobId);
		assertThat(redisTemplate.hasKey("worker:heartbeat:" + workerIdentity.workerId())).isTrue();
	}

	@Test
	void extendingIsSkippedForAJobThatIsNoLongerRunning() {
		Long jobId = claimFreshJobAsThisWorker();
		// Only the state is moved here, leaving lease_expires_at exactly as the claim query set
		// it. JobCompletionRepository.markSucceeded would have nulled it as well, which would make
		// "was it extended?" unfalsifiable -- null stays null whether the guard fired or not. A
		// stale-but-present lease is the sharper fixture: if the guard were missing, the refresh
		// below would visibly push this timestamp forward.
		jdbcTemplate.update("UPDATE jobs SET state = CAST('SUCCEEDED' AS job_state) WHERE id = ?", jobId);
		Instant leaseBeforeRefresh = leaseExpiresAtOf(jobId);

		// extendLease's "AND state = 'RUNNING'" guard: a job that finished (or was reclaimed)
		// between startTracking and the refresh tick must not have its lease resurrected, which
		// would otherwise hand a reclaimed job a live-looking lease it no longer deserves.
		trackAsInFlight(jobId);
		heartbeatService.refresh();

		assertThat(leaseExpiresAtOf(jobId)).isEqualTo(leaseBeforeRefresh);
		assertThat(stateOf(jobId)).isEqualTo("SUCCEEDED");
	}

	// Inserts a job and claims it as this worker, retrying if the real poll loop got there first.
	// @Scheduled fires JobPoller once at context startup regardless of how far out
	// poll-interval-ms is pushed, and that one cycle can overlap the first test in the class and
	// claim the fixture. Rather than sleeping to dodge it, each attempt forces the row back to a
	// claimable PENDING/due state and re-claims; after the startup cycle has passed, the next poll
	// is an hour away and the first attempt wins outright.
	//
	// The retry is bounded and ends in an assertion, so a claim query that genuinely stopped
	// claiming due PENDING work still fails this test rather than looping forever.
	// A losing attempt discards its row and inserts a new one rather than resetting the old one in
	// place: once the poll loop has run a job it may sit in SUCCEEDED, and SUCCEEDED -> PENDING is
	// not a legal transition under trg_jobs_state_transition. Forcing it would mean disabling the
	// trigger, i.e. weakening the very invariant the schema exists to enforce, to make a test
	// convenient. A fresh row costs nothing and keeps every write the test performs legal.
	private Long claimFreshJobAsThisWorker() {
		for (int attempt = 0; attempt < 20; attempt++) {
			Long jobId = insertDuePendingJob();
			List<ClaimedJob> claimed = jobClaimRepository.claim(workerIdentity.workerId(), 5, 2);
			if (claimed.stream().anyMatch(job -> job.id().equals(jobId))) {
				return jobId;
			}
			jdbcTemplate.update("DELETE FROM jobs WHERE id = ?", jobId);
		}
		throw new AssertionError("could not claim a fixture job as this worker after 20 attempts");
	}

	// LeaseManager is a single AtomicReference shared with the real JobPoller, whose finally block
	// clears it after every execution. Asserting the tracking stuck means a background poll cycle
	// wiping it fails here, where the cause is obvious, instead of silently turning
	// heartbeatService.refresh() into a no-op that looks like a broken lease extension.
	private void trackAsInFlight(Long jobId) {
		leaseManager.startTracking(jobId);
		assertThat(leaseManager.inFlightJobId()).contains(jobId);
	}

	private Instant expireLease(Long jobId) {
		Instant lapsed = Instant.now().minusSeconds(5);
		jdbcTemplate.update("UPDATE jobs SET lease_expires_at = ? WHERE id = ?", Timestamp.from(lapsed), jobId);
		return leaseExpiresAtOf(jobId);
	}

	private List<Long> reaperCandidateIdsWithExpiredLease() {
		return jdbcTemplate.queryForList(
				"SELECT id FROM jobs WHERE state = 'RUNNING' AND lease_expires_at < now()", Long.class);
	}

	private Instant leaseExpiresAtOf(Long jobId) {
		Timestamp lease = jdbcTemplate.queryForObject(
				"SELECT lease_expires_at FROM jobs WHERE id = ?", Timestamp.class, jobId);
		return lease != null ? lease.toInstant() : null;
	}

	private String stateOf(Long jobId) {
		return jdbcTemplate.queryForObject("SELECT state::text FROM jobs WHERE id = ?", String.class, jobId);
	}

	private Long insertDuePendingJob() {
		return jdbcTemplate.queryForObject("""
				INSERT INTO jobs (idempotency_key, job_type, payload, state, priority, max_attempts, next_run_at, created_at, updated_at)
				VALUES (?, 'report-generation', CAST(? AS jsonb), 'PENDING', 0, 5, ?, now(), now())
				RETURNING id
				""",
				Long.class,
				KEY_PREFIX + UUID.randomUUID(),
				// durationMillis 0, not the handler's 3000ms default: if a stray poll cycle ever
				// does execute this fixture, it should finish instantly rather than burn three
				// seconds of CPU inside another test's timing window.
				objectMapper.createObjectNode().put("durationMillis", 0).toString(),
				Timestamp.from(Instant.now().minusSeconds(5)));
	}
}
