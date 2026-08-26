"use client";

import { useMemo } from "react";
import { api } from "@/lib/api";
import { usePolling } from "@/lib/usePolling";
import type { JobResponse, JobState } from "@/lib/types";

const STATE_FILL: Record<JobState, string> = {
  PENDING: "#404040",
  RUNNING: "#1d4ed8",
  SUCCEEDED: "#15803d",
  DEAD_LETTER: "#991b1b",
  CANCELLED: "#262626",
};

const COLUMN_WIDTH = 180;
const ROW_HEIGHT = 60;
const NODE_RADIUS = 22;

interface LaidOutNode {
  job: JobResponse;
  x: number;
  y: number;
}

export function DagView() {
  const { data: edges, error: edgesError } = usePolling(api.listDependencyEdges, 5000);
  const { data: jobsPage, error: jobsError } = usePolling(() => api.listJobs({ size: 200 }), 5000);

  const layout = useMemo(() => {
    if (!edges || !jobsPage) return null;
    return computeLayout(edges, jobsPage.content);
  }, [edges, jobsPage]);

  if (edgesError || jobsError) {
    return <p className="text-sm text-red-400">{(edgesError ?? jobsError)?.message}</p>;
  }
  if (!layout) return <p className="text-sm text-neutral-400">Loading...</p>;
  if (layout.nodes.length === 0) {
    return (
      <p className="text-sm text-neutral-500">
        No jobs currently have dependencies. Submit one with &ldquo;Depends on&rdquo; set to see the graph.
      </p>
    );
  }

  const width = (layout.maxDepth + 1) * COLUMN_WIDTH;
  const height = (layout.maxRow + 1) * ROW_HEIGHT + 40;

  return (
    <div className="flex w-full justify-center rounded border border-neutral-800 bg-neutral-950/40 p-4">
      <svg
        viewBox={`0 0 ${width} ${height}`}
        preserveAspectRatio="xMidYMin meet"
        className="w-full"
        style={{ maxHeight: 560 }}
      >
        {layout.edges.map(([from, to], i) => (
          <line
            key={i}
            x1={from.x}
            y1={from.y}
            x2={to.x}
            y2={to.y}
            stroke="#525252"
            strokeWidth={1.5}
            markerEnd="url(#arrow)"
          />
        ))}
        <defs>
          <marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
            <path d="M0,0 L8,4 L0,8 Z" fill="#525252" />
          </marker>
        </defs>
        {layout.nodes.map((node) => (
          <g key={node.job.id}>
            <circle cx={node.x} cy={node.y} r={NODE_RADIUS} fill={STATE_FILL[node.job.state]} stroke="#171717" strokeWidth={2} />
            <text x={node.x} y={node.y + 4} textAnchor="middle" fontSize={12} fill="white" fontFamily="monospace">
              {node.job.id}
            </text>
            <text x={node.x} y={node.y + NODE_RADIUS + 14} textAnchor="middle" fontSize={10} fill="#a3a3a3">
              {node.job.jobType}
            </text>
          </g>
        ))}
      </svg>
    </div>
  );
}

function computeLayout(edges: { jobId: number; dependsOnId: number }[], jobs: JobResponse[]) {
  const jobsById = new Map(jobs.map((j) => [j.id, j]));
  const dependsOn = new Map<number, number[]>();
  const participating = new Set<number>();

  for (const edge of edges) {
    dependsOn.set(edge.jobId, [...(dependsOn.get(edge.jobId) ?? []), edge.dependsOnId]);
    participating.add(edge.jobId);
    participating.add(edge.dependsOnId);
  }


  const depthCache = new Map<number, number>();
  function depthOf(id: number, seen: Set<number> = new Set()): number {
    if (depthCache.has(id)) return depthCache.get(id)!;
    if (seen.has(id)) return 0;
    seen.add(id);
    const deps = dependsOn.get(id) ?? [];
    const depth = deps.length === 0 ? 0 : 1 + Math.max(...deps.map((d) => depthOf(d, seen)));
    depthCache.set(id, depth);
    return depth;
  }

  const byDepth = new Map<number, number[]>();
  let maxDepth = 0;
  for (const id of participating) {
    const depth = depthOf(id);
    maxDepth = Math.max(maxDepth, depth);
    byDepth.set(depth, [...(byDepth.get(depth) ?? []), id]);
  }

  const positions = new Map<number, LaidOutNode>();
  let maxRow = 0;
  for (const [depth, ids] of byDepth.entries()) {
    ids.forEach((id, row) => {
      maxRow = Math.max(maxRow, row);
      const job = jobsById.get(id);
      if (job) {
        positions.set(id, {
          job,
          x: depth * COLUMN_WIDTH + COLUMN_WIDTH / 2,
          y: row * ROW_HEIGHT + ROW_HEIGHT / 2 + 10,
        });
      }
    });
  }

  const nodes = [...positions.values()];
  const laidOutEdges: [LaidOutNode, LaidOutNode][] = [];
  for (const edge of edges) {
    const from = positions.get(edge.jobId);
    const to = positions.get(edge.dependsOnId);
    if (from && to) laidOutEdges.push([from, to]);
  }

  return { nodes, edges: laidOutEdges, maxDepth, maxRow };
}
