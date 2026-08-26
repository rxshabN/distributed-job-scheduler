"use client";

import { useCallback, useState } from "react";
import { api, ApiRequestError } from "@/lib/api";
import { usePolling } from "@/lib/usePolling";
import type { JobDetailResponse, JobState } from "@/lib/types";

const STATES: JobState[] = ["PENDING", "RUNNING", "SUCCEEDED", "DEAD_LETTER", "CANCELLED"];

const STATE_COLORS: Record<JobState, string> = {
  PENDING: "bg-neutral-700 text-neutral-200",
  RUNNING: "bg-blue-700 text-blue-100",
  SUCCEEDED: "bg-green-700 text-green-100",
  DEAD_LETTER: "bg-red-800 text-red-100",
  CANCELLED: "bg-neutral-800 text-neutral-400",
};

export function JobsView({ refreshToken }: { refreshToken: number }) {
  const [stateFilter, setStateFilter] = useState<JobState | "">("");
  const [page, setPage] = useState(0);
  const [selectedJobId, setSelectedJobId] = useState<number | null>(null);

  const fetchJobs = useCallback(
    () =>
      api.listJobs({
        state: stateFilter || undefined,
        page,
        size: 10,
      }),
    [stateFilter, page],
  );

  const { data, error, loading } = usePolling(fetchJobs, 3000);
  void refreshToken;

  return (
    <div>
      <div className="mb-4 flex gap-3">
        <select
          value={stateFilter}
          onChange={(e) => {
            setStateFilter(e.target.value as JobState | "");
            setPage(0);
          }}
          className="rounded border border-neutral-700 bg-neutral-900 px-3 py-1.5 text-sm text-neutral-100"
        >
          <option value="">All states</option>
          {STATES.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </div>

      {error && <p className="text-sm text-red-400">{error.message}</p>}
      {loading && !data && <p className="text-sm text-neutral-400">Loading...</p>}

      {data && (
        <>
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="border-b border-neutral-800 text-neutral-400">
                <th className="py-2 pr-4">ID</th>
                <th className="py-2 pr-4">Type</th>
                <th className="py-2 pr-4">State</th>
                <th className="py-2 pr-4">Attempt</th>
                <th className="py-2 pr-4">Next run</th>
                <th className="py-2 pr-4">Claimed by</th>
              </tr>
            </thead>
            <tbody>
              {data.content.map((job) => (
                <tr
                  key={job.id}
                  onClick={() => setSelectedJobId(job.id)}
                  className="cursor-pointer border-b border-neutral-900 hover:bg-neutral-900"
                >
                  <td className="py-2 pr-4 font-mono">{job.id}</td>
                  <td className="py-2 pr-4">{job.jobType}</td>
                  <td className="py-2 pr-4">
                    <span className={`rounded px-2 py-0.5 text-xs ${STATE_COLORS[job.state]}`}>{job.state}</span>
                  </td>
                  <td className="py-2 pr-4">
                    {job.attemptCount}/{job.maxAttempts}
                  </td>
                  <td className="py-2 pr-4">{new Date(job.nextRunAt).toLocaleString()}</td>
                  <td className="py-2 pr-4 font-mono text-xs">{job.claimedBy ?? "—"}</td>
                </tr>
              ))}
              {data.content.length === 0 && (
                <tr>
                  <td colSpan={6} className="py-4 text-center text-neutral-500">
                    No jobs match this filter.
                  </td>
                </tr>
              )}
            </tbody>
          </table>

          <div className="mt-3 flex items-center gap-3 text-sm text-neutral-400">
            <button
              disabled={page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              className="rounded border border-neutral-700 px-2 py-1 disabled:opacity-40"
            >
              Prev
            </button>
            <span>
              Page {data.page.number + 1} of {Math.max(1, data.page.totalPages)} ({data.page.totalElements} total)
            </span>
            <button
              disabled={page + 1 >= data.page.totalPages}
              onClick={() => setPage((p) => p + 1)}
              className="rounded border border-neutral-700 px-2 py-1 disabled:opacity-40"
            >
              Next
            </button>
          </div>
        </>
      )}

      {selectedJobId !== null && (
        <JobDetailPanel jobId={selectedJobId} onClose={() => setSelectedJobId(null)} />
      )}
    </div>
  );
}

function JobDetailPanel({ jobId, onClose }: { jobId: number; onClose: () => void }) {
  const { data, error, refresh } = usePolling<JobDetailResponse>(() => api.getJob(jobId), 3000);
  const [actionError, setActionError] = useState<string | null>(null);

  async function handleCancel() {
    setActionError(null);
    try {
      await api.cancelJob(jobId);
      refresh();
    } catch (err) {
      setActionError(err instanceof ApiRequestError ? err.message : String(err));
    }
  }

  async function handleRetry() {
    setActionError(null);
    try {
      await api.retryJob(jobId);
      refresh();
    } catch (err) {
      setActionError(err instanceof ApiRequestError ? err.message : String(err));
    }
  }

  return (
    <div className="fixed inset-0 flex items-center justify-center bg-black/60 p-4" onClick={onClose}>
      <div
        onClick={(e) => e.stopPropagation()}
        className="max-h-[80vh] w-full max-w-2xl overflow-y-auto rounded-lg border border-neutral-800 bg-neutral-950 p-6"
      >
        <div className="mb-4 flex items-center justify-between">
          <h3 className="text-lg font-semibold text-neutral-100">Job #{jobId}</h3>
          <button
            onClick={onClose}
            className="rounded border border-neutral-700 px-3 py-1.5 text-xs text-neutral-300 hover:bg-neutral-900 hover:text-neutral-100"
          >
            Close
          </button>
        </div>

        {error && <p className="text-sm text-red-400">{error.message}</p>}

        {data && (
          <div className="space-y-4 text-sm text-neutral-300">
            <div className="grid grid-cols-2 gap-2">
              <div>State: <span className="font-medium text-neutral-100">{data.job.state}</span></div>
              <div>Type: {data.job.jobType}</div>
              <div>Attempts: {data.job.attemptCount}/{data.job.maxAttempts}</div>
              <div>Priority: {data.job.priority}</div>
              <div>Claimed by: {data.job.claimedBy ?? "—"}</div>
              <div>Lease expires: {data.job.leaseExpiresAt ? new Date(data.job.leaseExpiresAt).toLocaleString() : "—"}</div>
            </div>

            {data.dependsOn.length > 0 && (
              <div>
                <h4 className="mb-1 font-medium text-neutral-100">Depends on</h4>
                <p>{data.dependsOn.join(", ")}</p>
              </div>
            )}

            <div>
              <h4 className="mb-1 font-medium text-neutral-100">Payload</h4>
              <pre className="overflow-x-auto rounded bg-neutral-900 p-2 text-xs">{JSON.stringify(data.job.payload, null, 2)}</pre>
            </div>

            <div>
              <h4 className="mb-1 font-medium text-neutral-100">Attempt history</h4>
              {data.executions.length === 0 ? (
                <p className="text-neutral-500">No attempts yet.</p>
              ) : (
                <table className="w-full text-left text-xs">
                  <thead>
                    <tr className="text-neutral-500">
                      <th className="py-1 pr-3">#</th>
                      <th className="py-1 pr-3">Worker</th>
                      <th className="py-1 pr-3">Started</th>
                      <th className="py-1 pr-3">Result</th>
                      <th className="py-1 pr-3">Error</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.executions.map((exec) => (
                      <tr key={exec.id} className="border-t border-neutral-900">
                        <td className="py-1 pr-3">{exec.attempt}</td>
                        <td className="py-1 pr-3 font-mono">{exec.workerId}</td>
                        <td className="py-1 pr-3">{new Date(exec.startedAt).toLocaleTimeString()}</td>
                        <td className="py-1 pr-3">{exec.success === null ? "in progress" : exec.success ? "success" : "failed"}</td>
                        <td className="py-1 pr-3 text-red-400">{exec.errorMessage ?? "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>

            <div className="flex gap-2 pt-2">
              {data.job.state === "PENDING" && (
                <button onClick={handleCancel} className="rounded border border-neutral-700 px-3 py-1.5 text-xs hover:bg-neutral-900">
                  Cancel job
                </button>
              )}
              {data.job.state === "DEAD_LETTER" && (
                <button onClick={handleRetry} className="rounded border border-neutral-700 px-3 py-1.5 text-xs hover:bg-neutral-900">
                  Retry job
                </button>
              )}
            </div>
            {actionError && <p className="text-xs text-red-400">{actionError}</p>}
          </div>
        )}
      </div>
    </div>
  );
}
