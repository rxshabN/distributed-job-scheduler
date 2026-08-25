package com.rishab.scheduler.jobs;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JobDependencyRepository {

	private final JdbcTemplate jdbcTemplate;

	public JobDependencyRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<Long> findDependencyIds(Long jobId) {
		return jdbcTemplate.queryForList("SELECT depends_on_id FROM job_dependencies WHERE job_id = ?", Long.class, jobId);
	}

	public List<JobDependencyEdge> findAllEdges() {
		return jdbcTemplate.query("SELECT job_id, depends_on_id FROM job_dependencies",
				(rs, rowNum) -> new JobDependencyEdge(rs.getLong("job_id"), rs.getLong("depends_on_id")));
	}

	public void insertEdges(Long jobId, Set<Long> dependsOnIds) {
		for (Long dependsOnId : dependsOnIds) {
			jdbcTemplate.update("INSERT INTO job_dependencies (job_id, depends_on_id) VALUES (?, ?)", jobId, dependsOnId);
		}
	}

	// Existence check for dependsOn validation -- returns the subset of the given ids that don't
	// actually correspond to a job row, so the caller can name exactly which ones are bad.
	// Plain JdbcTemplate has no built-in "bind a collection as IN (...)" support the way
	// NamedParameterJdbcTemplate does, and `= ANY(?)` needs an explicit java.sql.Array rather than
	// a plain placeholder -- a hand-built IN (?,?,?) with one placeholder per id is the simplest
	// correct option without pulling in a second JdbcTemplate flavor for one query.
	public Set<Long> findMissingJobIds(Set<Long> ids) {
		if (ids.isEmpty()) {
			return Set.of();
		}
		String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
		List<Long> existing = jdbcTemplate.queryForList(
				"SELECT id FROM jobs WHERE id IN (" + placeholders + ")", Long.class, ids.toArray());
		return ids.stream().filter(id -> !existing.contains(id)).collect(Collectors.toSet());
	}
}
