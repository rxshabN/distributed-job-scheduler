package com.rishab.scheduler.workers;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workers")
public class WorkerController {

	private final WorkerHeartbeatService workerHeartbeatService;

	public WorkerController(WorkerHeartbeatService workerHeartbeatService) {
		this.workerHeartbeatService = workerHeartbeatService;
	}

	@GetMapping
	public List<WorkerResponse> list() {
		return workerHeartbeatService.listAliveWorkers();
	}
}
