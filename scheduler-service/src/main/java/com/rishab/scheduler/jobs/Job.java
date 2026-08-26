package com.rishab.scheduler.jobs;

import tools.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "jobs")
public class Job {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "idempotency_key", nullable = false, unique = true)
	private String idempotencyKey;

	@Column(name = "job_type", nullable = false)
	private String jobType;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", nullable = false)
	private JsonNode payload;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "state", nullable = false)
	private JobState state = JobState.PENDING;

	@Column(nullable = false)
	private int priority;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "max_attempts", nullable = false)
	private int maxAttempts;

	@Column(name = "next_run_at", nullable = false)
	private Instant nextRunAt;

	@Column(name = "claimed_by")
	private String claimedBy;

	@Column(name = "lease_expires_at")
	private Instant leaseExpiresAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Job() {
		// JPA requires a no-arg constructor for entity classes. It can be protected or private, but not public.
	}

	public Job(String idempotencyKey, String jobType, JsonNode payload, int priority, int maxAttempts,
			Instant nextRunAt) {
		this.idempotencyKey = idempotencyKey;
		this.jobType = jobType;
		this.payload = payload;
		this.priority = priority;
		this.maxAttempts = maxAttempts;
		this.nextRunAt = nextRunAt;
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getJobType() {
		return jobType;
	}

	public JsonNode getPayload() {
		return payload;
	}

	public JobState getState() {
		return state;
	}

	public int getPriority() {
		return priority;
	}

	public int getAttemptCount() {
		return attemptCount;
	}

	public int getMaxAttempts() {
		return maxAttempts;
	}

	public Instant getNextRunAt() {
		return nextRunAt;
	}

	public String getClaimedBy() {
		return claimedBy;
	}

	public Instant getLeaseExpiresAt() {
		return leaseExpiresAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
