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

@Component
class DependencyCycleDetector {

	Optional<List<Long>> findCycle(List<JobDependencyEdge> existingEdges, Long newJobId, Set<Long> newJobDependsOn) {
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
