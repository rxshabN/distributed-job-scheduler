package com.rishab.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SchedulerServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
