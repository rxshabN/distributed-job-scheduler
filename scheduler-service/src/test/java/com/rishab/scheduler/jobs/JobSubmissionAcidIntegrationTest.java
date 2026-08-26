package com.rishab.scheduler.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rishab.scheduler.TestcontainersConfiguration;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JobSubmissionAcidIntegrationTest {

	private static final String KEY_PREFIX = "acid-test-";
	private static final int CONCURRENT_SUBMITTERS = 8;

	@Autowired
	private JobService jobService;

	@Autowired
	private JobRepository jobRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void clearPreviousRun() {
		jdbcTemplate.update("DELETE FROM jobs WHERE idempotency_key LIKE ?", KEY_PREFIX + "%");
	}

	@Test
	void concurrentSubmissionsOfOneIdempotencyKeyCreateExactlyOneRow() throws Exception {
		String idempotencyKey = KEY_PREFIX + UUID.randomUUID();

		List<JobSubmissionResult> results = submitConcurrently(idempotencyKey, CONCURRENT_SUBMITTERS);

		assertThat(results.stream().filter(JobSubmissionResult::created).count()).isEqualTo(1);
		Set<Long> distinctIds = results.stream().map(result -> result.job().getId()).collect(java.util.stream.Collectors.toSet());
		assertThat(distinctIds).hasSize(1);

		Integer rowCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM jobs WHERE idempotency_key = ?", Integer.class, idempotencyKey);
		assertThat(rowCount).isEqualTo(1);
	}

	@Test
	void aFailedSubmissionLeavesNoOrphanedDependencyEdges() {

		Long dependency = submitFresh().job().getId();
		String contestedKey = KEY_PREFIX + UUID.randomUUID();

		JobSubmissionResult first = jobService.submit(request(contestedKey, List.of(dependency)));
		assertThat(first.created()).isTrue();

		JobSubmissionResult second = jobService.submit(request(contestedKey, List.of(dependency)));
		assertThat(second.created()).isFalse();
		assertThat(second.job().getId()).isEqualTo(first.job().getId());

		Integer edgeCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM job_dependencies WHERE job_id = ?", Integer.class, first.job().getId());
		assertThat(edgeCount).isEqualTo(1);

		Integer orphanEdges = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM job_dependencies jd
				WHERE NOT EXISTS (SELECT 1 FROM jobs j WHERE j.id = jd.job_id)
				""", Integer.class);
		assertThat(orphanEdges).isZero();
	}

	@Test
	void aRejectedDependencyReferenceCreatesNoJobRowAtAll() {
		String key = KEY_PREFIX + UUID.randomUUID();
		long unknownJobId = 999_999_999L;

		assertThatThrownBy(() -> jobService.submit(request(key, List.of(unknownJobId))))
				.isInstanceOf(UnknownDependencyException.class);

		assertThat(jobRepository.findByIdempotencyKey(key)).isEmpty();
	}

	@Test
	void acommittedSubmissionIsVisibleToAFreshConnectionOutsideTheSubmittingTransaction() {

		JobSubmissionResult result = submitFresh();

		String stateOnAnotherConnection = jdbcTemplate.queryForObject(
				"SELECT state::text FROM jobs WHERE id = ?", String.class, result.job().getId());
		assertThat(stateOnAnotherConnection).isEqualTo("PENDING");

		Integer attemptCount = jdbcTemplate.queryForObject(
				"SELECT attempt_count FROM jobs WHERE id = ?", Integer.class, result.job().getId());
		assertThat(attemptCount).isZero();
	}

	private List<JobSubmissionResult> submitConcurrently(String idempotencyKey, int submitters) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(submitters);
		CountDownLatch startGate = new CountDownLatch(1);
		try {
			List<Future<JobSubmissionResult>> futures = new ArrayList<>();
			for (int i = 0; i < submitters; i++) {
				Callable<JobSubmissionResult> task = () -> {
					startGate.await();
					return jobService.submit(request(idempotencyKey, List.of()));
				};
				futures.add(executor.submit(task));
			}
			startGate.countDown();

			List<JobSubmissionResult> results = new ArrayList<>();
			for (Future<JobSubmissionResult> future : futures) {
				results.add(future.get(60, TimeUnit.SECONDS));
			}
			return results;
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}
	}

	private JobSubmissionResult submitFresh() {
		return jobService.submit(request(KEY_PREFIX + UUID.randomUUID(), List.of()));
	}

	private JobSubmissionRequest request(String idempotencyKey, List<Long> dependsOn) {
		return new JobSubmissionRequest(
				"email-simulation",
				objectMapper.createObjectNode(),
				idempotencyKey,
				0,
				5,
				null,
				dependsOn);
	}
}
