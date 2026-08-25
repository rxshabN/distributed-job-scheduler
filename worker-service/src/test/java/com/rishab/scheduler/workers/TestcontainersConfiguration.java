package com.rishab.scheduler.workers;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// Public so feature-package tests (e.g. com.rishab.scheduler.workers.jobs) can @Import it too --
// package-by-feature means tests won't all live in this root package. Pinned to postgres:16-alpine
// / redis:7-alpine (not the Initializr-generated :latest) to match docker-compose.yml and
// scheduler-service's own TestcontainersConfiguration -- both services and local dev should run
// the same versions production will, not whatever :latest happens to resolve to on test day.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
	}

}
