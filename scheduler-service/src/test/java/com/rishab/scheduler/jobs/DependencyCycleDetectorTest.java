package com.rishab.scheduler.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

// Pure unit test of the Kahn's-algorithm cycle check (ROADMAP.md Weekend 5 item 2). The cycle
// case here is hand-constructed directly against the algorithm -- as JobService's own comment on
// PENDING_SUBMISSION_SENTINEL_ID explains, a real cycle can never actually arise through the
// submission API (a new job only ever adds outgoing edges to already-existing jobs, never
// incoming ones), so this is the only way to exercise that branch at all.
class DependencyCycleDetectorTest {

	private final DependencyCycleDetector detector = new DependencyCycleDetector();

	@Test
	void reportsNoCycleForANewJobDependingOnIndependentExistingJobs() {
		List<JobDependencyEdge> existing = List.of(new JobDependencyEdge(2L, 1L));

		assertThat(detector.findCycle(existing, 3L, Set.of(1L, 2L))).isEmpty();
	}

	@Test
	void reportsNoCycleForAnEmptyGraph() {
		assertThat(detector.findCycle(List.of(), 1L, Set.of())).isEmpty();
	}

	@Test
	void detectsACycleInAHandConstructedGraph() {
		// 1 depends on 2, 2 depends on 3 -- then the new submission (id 3) proposes to depend on
		// 1, closing the loop 1 -> 2 -> 3 -> 1. Only reachable by constructing the edge list
		// directly like this, not through JobService's real validation path.
		List<JobDependencyEdge> existing = List.of(
				new JobDependencyEdge(1L, 2L),
				new JobDependencyEdge(2L, 3L));

		assertThat(detector.findCycle(existing, 3L, Set.of(1L))).isPresent();
	}
}
