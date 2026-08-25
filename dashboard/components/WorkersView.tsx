"use client";

import { api } from "@/lib/api";
import { usePolling } from "@/lib/usePolling";

export function WorkersView() {
  const { data, error, loading } = usePolling(api.listWorkers, 3000);

  if (loading && !data) return <p className="text-sm text-neutral-400">Loading...</p>;
  if (error) return <p className="text-sm text-red-400">{error.message}</p>;

  return (
    <div>
      <p className="mb-4 text-sm text-neutral-400">
        Workers with a live Redis heartbeat (spec §5) -- a worker missing here for more than its
        heartbeat TTL is either stopped or partitioned from Redis.
      </p>
      <table className="w-full text-left text-sm">
        <thead>
          <tr className="border-b border-neutral-800 text-neutral-400">
            <th className="py-2 pr-4">Worker ID</th>
            <th className="py-2 pr-4">Last seen</th>
          </tr>
        </thead>
        <tbody>
          {data?.map((worker) => (
            <tr key={worker.workerId} className="border-b border-neutral-900">
              <td className="py-2 pr-4 font-mono">{worker.workerId}</td>
              <td className="py-2 pr-4">{worker.lastSeenAt ? new Date(worker.lastSeenAt).toLocaleString() : "—"}</td>
            </tr>
          ))}
          {data?.length === 0 && (
            <tr>
              <td colSpan={2} className="py-4 text-center text-neutral-500">
                No workers currently heartbeating.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
