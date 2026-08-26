package com.rishab.scheduler.jobs;

import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

	private final JobService jobService;

	public JobController(JobService jobService) {
		this.jobService = jobService;
	}

	@PostMapping
	public ResponseEntity<JobResponse> submit(@Valid @RequestBody JobSubmissionRequest request) {
		JobSubmissionResult result = jobService.submit(request);
		HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
		return ResponseEntity.status(status).body(JobMapper.toResponse(result.job()));
	}

	@GetMapping("/{id}")
	public JobDetailResponse getById(@PathVariable Long id) {
		return jobService.getDetail(id);
	}

	@PostMapping("/{id}/cancel")
	public JobResponse cancel(@PathVariable Long id) {
		return JobMapper.toResponse(jobService.cancel(id));
	}

	@PostMapping("/{id}/retry")
	public JobResponse retry(@PathVariable Long id) {
		return JobMapper.toResponse(jobService.retry(id));
	}

	@GetMapping
	public PagedModel<JobResponse> list(
			@RequestParam(required = false) JobState state,
			@RequestParam(required = false) String jobType,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return new PagedModel<>(jobService.list(state, jobType, pageable).map(JobMapper::toResponse));
	}
}
