package com.rishab.scheduler.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.rishab.scheduler.TestcontainersConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// Proves the whole scaffolding stack actually works together, not just that each piece
// compiles in isolation: real Postgres + Redis via Testcontainers, Flyway migrating on
// startup, and Hibernate round-tripping the Postgres enum + JSONB mapping from spec §3.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SchemaMigrationIntegrationTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JobRepository jobRepository;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void flywayAppliesV1InitialSchemaOnStartup() {
		Integer appliedCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = true",
				Integer.class);

		assertThat(appliedCount).isEqualTo(1);
	}

	@Test
	@Transactional
	void jobStateAndPayloadRoundTripThroughPostgresEnumAndJsonb() throws Exception {
		JsonNode payload = objectMapper.readTree("{\"url\":\"https://example.com\"}");
		Job job = new Job("idem-key-1", "http-callback", payload, 5, 3, Instant.now());

		Job saved = jobRepository.saveAndFlush(job);
		Job found = jobRepository.findById(saved.getId()).orElseThrow();

		assertThat(found.getIdempotencyKey()).isEqualTo("idem-key-1");
		assertThat(found.getState()).isEqualTo(JobState.PENDING);
		assertThat(found.getPayload().get("url").asString()).isEqualTo("https://example.com");
	}
}
