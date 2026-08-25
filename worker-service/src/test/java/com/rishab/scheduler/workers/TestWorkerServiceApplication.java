package com.rishab.scheduler.workers;

import org.springframework.boot.SpringApplication;

public class TestWorkerServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(WorkerServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
