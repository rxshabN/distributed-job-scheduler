package com.rishab.scheduler.jobs;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Not in spec §7's original API table -- added because the dashboard's DAG visualization
// (ROADMAP.md Weekend 5 item 4) needs every dependency edge to render the graph, and neither the
// paginated job list nor a single job's detail response can supply that without either an N+1
// fetch loop or exposing dependsOn on every list row (see JobDetailResponse's comment for why
// that was avoided). One cheap query covering the whole edge set is simpler than either.
@RestController
@RequestMapping("/api/v1/job-dependencies")
public class JobDependencyController {

	private final JobDependencyRepository jobDependencyRepository;

	public JobDependencyController(JobDependencyRepository jobDependencyRepository) {
		this.jobDependencyRepository = jobDependencyRepository;
	}

	@GetMapping
	public List<JobDependencyEdge> list() {
		return jobDependencyRepository.findAllEdges();
	}
}
