package com.rishab.scheduler.jobs;

// created distinguishes a brand-new job (submit() should answer 201) from a resubmission of a
// known idempotency_key (spec §7: answer 200 with the existing job, not a duplicate or an error).
public record JobSubmissionResult(Job job, boolean created) {
}
