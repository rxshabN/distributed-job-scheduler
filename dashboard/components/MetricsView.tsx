"use client";

import { api } from "@/lib/api";
import { usePolling } from "@/lib/usePolling";
import type { JobState } from "@/lib/types";

const STATE_ORDER: JobState[] = ["PENDING", "RUNNING", "SUCCEEDED", "DEAD_LETTER", "CANCELLED"];

export function MetricsView() {
  const { data, error, loading } = usePolling(api.getStats, 5000);

  if (loading && !data) return <p className="text-sm text-neutral-400">Loading...</p>;
  if (error) return <p className="text-sm text-red-400">{error.message}</p>;
  if (!data) return null;

  const total = Object.values(data.countsByState).reduce((a, b) => a + b, 0);

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-5">
        {STATE_ORDER.map((state) => (
          <div key={state} className="rounded border border-neutral-800 p-4">
            <div className="text-2xl font-semibold text-neutral-100">{data.countsByState[state] ?? 0}</div>
            <div className="text-xs text-neutral-400">{state}</div>
          </div>
        ))}
      </div>

      <div className="rounded border border-neutral-800 p-4">
        <div className="text-2xl font-semibold text-neutral-100">{data.completedInLastFiveMinutes}</div>
        <div className="text-xs text-neutral-400">completed (succeeded or dead-lettered) in the last 5 minutes</div>
      </div>

      <p className="text-xs text-neutral-500">{data.latencyPercentilesNote}</p>
      <p className="text-xs text-neutral-600">Total jobs tracked: {total}</p>
    </div>
  );
}
