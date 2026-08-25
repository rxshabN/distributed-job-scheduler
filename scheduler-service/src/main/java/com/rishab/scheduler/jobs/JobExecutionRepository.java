package com.rishab.scheduler.jobs;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobExecutionRepository extends JpaRepository<JobExecution, Long> {

	List<JobExecution> findByJobIdOrderByAttemptAsc(Long jobId);
}
