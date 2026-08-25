package com.rishab.scheduler.jobs;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface JobRepository extends JpaRepository<Job, Long>, JpaSpecificationExecutor<Job> {

	Optional<Job> findByIdempotencyKey(String idempotencyKey);
}
