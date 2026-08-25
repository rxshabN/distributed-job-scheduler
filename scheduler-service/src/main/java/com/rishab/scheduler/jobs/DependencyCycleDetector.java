package com.rishab.scheduler.jobs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

// ROADMAP.md Weekend 5 item 2: "Topological sort with cycle detection -- Kahn's algorithm."
// Run at submission time against every existing job_dependencies edge plus the new job's proposed
// dependsOn edges.
//
// In practice this can never actually find a cycle given how submission works: a new job can only
// be the SOURCE of new edges (it depends on already-existing jobs), never the target -- nothing
// can depend on a job that doesn't exist yet, so no chain can loop back to it. An acyclic graph
// that only ever grows by appending a new leaf-with-outgoing-edges stays acyclic by construction.
// Implemented for real anyway: it's explicitly requested (real DSA practice), it's cheap at this
// project's scale, and it's the guard that would actually matter the moment any future endpoint
// let dependencies be attached to an *existing* job instead of only at creation time.
@Component
class DependencyCycleDetector {

	Optional<List<Long>> findCycle(List<JobDependencyEdge> existingEdges, Long newJobId, Set<Long> newJobDependsOn) {
		// dependsOnAdjacency: job -> the jobs it depends on (edge direction as stored in the table).
		// prerequisiteOf: dependency -> the jobs that depend on it (the reverse -- what Kahn's
		// algorithm actually walks, since it processes "no remaining prerequisites" nodes first).
		Map<Long, List<Long>> dependsOnAdjacency = new HashMap<>();
		Map<Long, List<Long>> prerequisiteOf = new HashMap<>();
		Set<Long> allNodes = new HashSet<>();

		for (JobDependencyEdge edge : existingEdges) {
			dependsOnAdjacency.computeIfAbsent(edge.jobId(), k -> new ArrayList<>()).add(edge.dependsOnId());
			prerequisiteOf.computeIfAbsent(edge.dependsOnId(), k -> new ArrayList<>()).add(edge.jobId());
			allNodes.add(edge.jobId());
			allNodes.add(edge.dependsOnId());
		}
		dependsOnAdjacency.computeIfAbsent(newJobId, k -> new ArrayList<>()).addAll(newJobDependsOn);
		for (Long dependsOnId : newJobDependsOn) {
			prerequisiteOf.computeIfAbsent(dependsOnId, k -> new ArrayList<>()).add(newJobId);
			allNodes.add(dependsOnId);
		}
		allNodes.add(newJobId);

		Map<Long, Integer> remainingPrerequisites = new HashMap<>();
		allNodes.forEach(node -> remainingPrerequisites.put(node, dependsOnAdjacency.getOrDefault(node, List.of()).size()));

		Deque<Long> ready = new ArrayDeque<>();
		remainingPrerequisites.forEach((node, count) -> {
			if (count == 0) {
				ready.add(node);
			}
		});

		int visited = 0;
		while (!ready.isEmpty()) {
			Long node = ready.poll();
			visited++;
			for (Long dependent : prerequisiteOf.getOrDefault(node, List.of())) {
				int updated = remainingPrerequisites.merge(dependent, -1, Integer::sum);
				if (updated == 0) {
					ready.add(dependent);
				}
			}
		}

		if (visited == allNodes.size()) {
			return Optional.empty();
		}

		Set<Long> stuck = allNodes.stream().filter(node -> remainingPrerequisites.get(node) > 0).collect(Collectors.toSet());
		return Optional.of(extractCyclePath(dependsOnAdjacency, stuck));
	}

	private static List<Long> extractCyclePath(Map<Long, List<Long>> dependsOnAdjacency, Set<Long> stuck) {
		Long start = stuck.iterator().next();
		List<Long> path = new ArrayList<>();
		Set<Long> seen = new LinkedHashSet<>();
		Long current = start;
		while (seen.add(current)) {
			path.add(current);
			current = dependsOnAdjacency.getOrDefault(current, List.of()).stream()
					.filter(stuck::contains)
					.findFirst()
					.orElse(start);
		}
		path.add(current);
		return path;
	}
}
