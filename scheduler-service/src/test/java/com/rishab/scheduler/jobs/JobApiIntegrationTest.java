package com.rishab.scheduler.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rishab.scheduler.TestcontainersConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

// Exercises the three Weekend 1 endpoints against real Postgres via Testcontainers -- no
// mocking the datastore, per CLAUDE.md. resubmittingKnownIdempotencyKeyReturnsExistingJob below
// checks the API *contract* (200 vs 201, same job id); the concurrency half of spec §11 test 3
// -- many submitters of one key racing, assert one row -- lives in
// JobSubmissionAcidIntegrationTest, because proving the uniqueness comes from the database rather
// than from a check-then-insert window needs concurrent callers, which MockMvc here never has.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class JobApiIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JobRepository jobRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void submitCreatesJobInPendingStateWithDefaults() throws Exception {
		Map<String, Object> request = Map.of(
				"jobType", "email-simulation",
				"payload", Map.of("to", "test@example.com"),
				"idempotencyKey", "idem-submit-1");

		mockMvc.perform(post("/api/v1/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.idempotencyKey").value("idem-submit-1"))
				.andExpect(jsonPath("$.jobType").value("email-simulation"))
				.andExpect(jsonPath("$.state").value("PENDING"))
				.andExpect(jsonPath("$.priority").value(0))
				.andExpect(jsonPath("$.maxAttempts").value(5))
				.andExpect(jsonPath("$.attemptCount").value(0))
				.andExpect(jsonPath("$.id").exists());
	}

	@Test
	void resubmittingKnownIdempotencyKeyReturnsExistingJobInstead() throws Exception {
		Map<String, Object> request = Map.of(
				"jobType", "http-callback",
				"payload", Map.of("url", "https://example.com"),
				"idempotencyKey", "idem-dup-1");
		String requestJson = objectMapper.writeValueAsString(request);

		String firstResponse = mockMvc.perform(post("/api/v1/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content(requestJson))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		Long firstId = objectMapper.readTree(firstResponse).get("id").asLong();

		mockMvc.perform(post("/api/v1/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content(requestJson))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(firstId))
				.andExpect(jsonPath("$.idempotencyKey").value("idem-dup-1"));

		assertThat(jobRepository.findAll().stream().filter(j -> "idem-dup-1".equals(j.getIdempotencyKey())).count())
				.isEqualTo(1);
	}

	@Test
	void submitRejectsMissingRequiredFields() throws Exception {
		Map<String, Object> request = Map.of("payload", Map.of());

		mockMvc.perform(post("/api/v1/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	void getByIdReturnsJobWithEmptyExecutionHistoryForFreshJob() throws Exception {
		JsonNodePayload payload = new JsonNodePayload(objectMapper);
		Job job = new Job("idem-detail-1", "report-generation", payload.of(Map.of("reportId", 42)), 2, 5, Instant.now());
		Job saved = jobRepository.saveAndFlush(job);

		mockMvc.perform(get("/api/v1/jobs/{id}", saved.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.job.id").value(saved.getId()))
				.andExpect(jsonPath("$.job.idempotencyKey").value("idem-detail-1"))
				.andExpect(jsonPath("$.executions").isArray())
				.andExpect(jsonPath("$.executions").isEmpty());
	}

	@Test
	void getByIdReturns404ForUnknownJob() throws Exception {
		mockMvc.perform(get("/api/v1/jobs/{id}", 999_999_999L))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	void listFiltersByStateAndJobType() throws Exception {
		JsonNodePayload payload = new JsonNodePayload(objectMapper);
		jobRepository.saveAndFlush(new Job("idem-list-1", "http-callback", payload.of(Map.of()), 0, 5, Instant.now()));
		jobRepository.saveAndFlush(new Job("idem-list-2", "email-simulation", payload.of(Map.of()), 0, 5, Instant.now()));

		mockMvc.perform(get("/api/v1/jobs")
						.param("state", "PENDING")
						.param("jobType", "http-callback"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[?(@.idempotencyKey == 'idem-list-1')]").exists())
				.andExpect(jsonPath("$.content[?(@.idempotencyKey == 'idem-list-2')]").doesNotExist());
	}

	@Test
	void listRejectsInvalidStateValue() throws Exception {
		mockMvc.perform(get("/api/v1/jobs").param("state", "NOT_A_STATE"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void cancelsAPendingJob() throws Exception {
		JsonNodePayload payload = new JsonNodePayload(objectMapper);
		Job job = jobRepository.saveAndFlush(new Job("idem-cancel-1", "http-callback", payload.of(Map.of()), 0, 5, Instant.now()));

		mockMvc.perform(post("/api/v1/jobs/{id}/cancel", job.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("CANCELLED"));
	}

	@Test
	void cancelRejectsANonPendingJobWith409() throws Exception {
		JsonNodePayload payload = new JsonNodePayload(objectMapper);
		Job job = jobRepository.saveAndFlush(new Job("idem-cancel-2", "http-callback", payload.of(Map.of()), 0, 5, Instant.now()));
		mockMvc.perform(post("/api/v1/jobs/{id}/cancel", job.getId())).andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/jobs/{id}/cancel", job.getId()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409));
	}

	@Test
	void retryRequeuesADeadLetterJobWithResetAttemptCount() throws Exception {
		JsonNodePayload payload = new JsonNodePayload(objectMapper);
		Job job = jobRepository.saveAndFlush(new Job("idem-retry-1", "http-callback", payload.of(Map.of()), 0, 1, Instant.now()));
		// Drive it to DEAD_LETTER the same way worker-service's JobCompletionRepository would --
		// via the trigger-respecting PENDING -> RUNNING -> DEAD_LETTER path, not a direct jump.
		markDeadLetter(job.getId());

		mockMvc.perform(post("/api/v1/jobs/{id}/retry", job.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("PENDING"))
				.andExpect(jsonPath("$.attemptCount").value(0));
	}

	@Test
	void retryRejectsANonDeadLetterJobWith409() throws Exception {
		JsonNodePayload payload = new JsonNodePayload(objectMapper);
		Job job = jobRepository.saveAndFlush(new Job("idem-retry-2", "http-callback", payload.of(Map.of()), 0, 5, Instant.now()));

		mockMvc.perform(post("/api/v1/jobs/{id}/retry", job.getId()))
				.andExpect(status().isConflict());
	}

	@Test
	void statsReturnsCountsByState() throws Exception {
		JsonNodePayload payload = new JsonNodePayload(objectMapper);
		jobRepository.saveAndFlush(new Job("idem-stats-1", "http-callback", payload.of(Map.of()), 0, 5, Instant.now()));

		mockMvc.perform(get("/api/v1/stats"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.countsByState.PENDING").exists())
				.andExpect(jsonPath("$.latencyPercentilesNote").exists());
	}

	@Test
	void submitWithDependsOnCreatesEdgesVisibleInDetail() throws Exception {
		JsonNodePayload payload = new JsonNodePayload(objectMapper);
		Job dependency = jobRepository.saveAndFlush(new Job("idem-dep-1", "http-callback", payload.of(Map.of()), 0, 5, Instant.now()));

		Map<String, Object> request = Map.of(
				"jobType", "report-generation",
				"payload", Map.of(),
				"idempotencyKey", "idem-dependent-1",
				"dependsOn", List.of(dependency.getId()));

		String response = mockMvc.perform(post("/api/v1/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		Long dependentId = objectMapper.readTree(response).get("id").asLong();

		mockMvc.perform(get("/api/v1/jobs/{id}", dependentId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dependsOn[0]").value(dependency.getId()));

		mockMvc.perform(get("/api/v1/job-dependencies"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.jobId == " + dependentId + ")]").exists());
	}

	@Test
	void submitRejectsDependsOnReferencingAnUnknownJobWith400() throws Exception {
		Map<String, Object> request = Map.of(
				"jobType", "report-generation",
				"payload", Map.of(),
				"idempotencyKey", "idem-dependent-2",
				"dependsOn", List.of(999_999_999L));

		mockMvc.perform(post("/api/v1/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isBadRequest());
	}

	private void markDeadLetter(Long jobId) {
		jdbcTemplate.update("UPDATE jobs SET state = CAST('RUNNING' AS job_state) WHERE id = ?", jobId);
		jdbcTemplate.update("UPDATE jobs SET state = CAST('DEAD_LETTER' AS job_state), attempt_count = 1 WHERE id = ?", jobId);
	}

	// Small local helper so tests can build a tools.jackson JsonNode payload from a plain Map
	// without repeating the ObjectMapper conversion at every call site.
	private record JsonNodePayload(ObjectMapper objectMapper) {
		tools.jackson.databind.JsonNode of(Map<String, ?> value) {
			return objectMapper.valueToTree(value);
		}
	}
}
