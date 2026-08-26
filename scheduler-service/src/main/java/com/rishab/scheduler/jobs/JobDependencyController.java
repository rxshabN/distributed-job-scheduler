package com.rishab.scheduler.jobs;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
