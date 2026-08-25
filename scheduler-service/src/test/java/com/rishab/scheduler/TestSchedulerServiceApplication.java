package com.rishab.scheduler;

import org.springframework.boot.SpringApplication;

public class TestSchedulerServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(SchedulerServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
