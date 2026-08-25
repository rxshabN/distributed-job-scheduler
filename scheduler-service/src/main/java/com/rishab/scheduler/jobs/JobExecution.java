package com.rishab.scheduler.jobs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "job_executions", uniqueConstraints = @UniqueConstraint(columnNames = { "job_id", "attempt" }))
public class JobExecution {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "job_id", nullable = false)
	private Job job;

	@Column(nullable = false)
	private int attempt;

	@Column(name = "worker_id", nullable = false)
	private String workerId;

	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@Column(name = "finished_at")
	private Instant finishedAt;

	private Boolean success;

	@Column(name = "error_message")
	private String errorMessage;

	protected JobExecution() {
		// JPA
	}

	public JobExecution(Job job, int attempt, String workerId, Instant startedAt) {
		this.job = job;
		this.attempt = attempt;
		this.workerId = workerId;
		this.startedAt = startedAt;
	}

	public Long getId() {
		return id;
	}

	public Job getJob() {
		return job;
	}

	public int getAttempt() {
		return attempt;
	}

	public String getWorkerId() {
		return workerId;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

	public Boolean getSuccess() {
		return success;
	}

	public String getErrorMessage() {
		return errorMessage;
	}
}
