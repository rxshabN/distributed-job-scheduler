import type {
  ApiError,
  JobDependencyEdge,
  JobDetailResponse,
  JobState,
  PagedJobs,
  StatsResponse,
  WorkerResponse,
} from "./types";

// Cross-origin by design (spec §12): the dashboard (Vercel) and API (Oracle VM) are never on the
// same origin in production, which is exactly why scheduler-service needs the CORS config noted
// in WebCorsConfig rather than this being same-origin-and-therefore-invisible in dev.
const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

class ApiRequestError extends Error {
  constructor(
    public status: number,
    public apiError: ApiError | null,
  ) {
    super(apiError?.message ?? `request failed with status ${status}`);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
    cache: "no-store",
  });
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new ApiRequestError(response.status, body);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return response.json() as Promise<T>;
}

export interface SubmitJobRequest {
  jobType: string;
  payload: unknown;
  idempotencyKey: string;
  priority?: number;
  maxAttempts?: number;
  runAt?: string;
  dependsOn?: number[];
}

export const api = {
  submitJob: (body: SubmitJobRequest) =>
    request<import("./types").JobResponse>("/api/v1/jobs", { method: "POST", body: JSON.stringify(body) }),

  getJob: (id: number) => request<JobDetailResponse>(`/api/v1/jobs/${id}`),

  listJobs: (params: { state?: JobState; jobType?: string; page?: number; size?: number } = {}) => {
    const query = new URLSearchParams();
    if (params.state) query.set("state", params.state);
    if (params.jobType) query.set("jobType", params.jobType);
    query.set("page", String(params.page ?? 0));
    query.set("size", String(params.size ?? 20));
    return request<PagedJobs>(`/api/v1/jobs?${query.toString()}`);
  },

  cancelJob: (id: number) => request<import("./types").JobResponse>(`/api/v1/jobs/${id}/cancel`, { method: "POST" }),

  retryJob: (id: number) => request<import("./types").JobResponse>(`/api/v1/jobs/${id}/retry`, { method: "POST" }),

  listWorkers: () => request<WorkerResponse[]>("/api/v1/workers"),

  getStats: () => request<StatsResponse>("/api/v1/stats"),

  listDependencyEdges: () => request<JobDependencyEdge[]>("/api/v1/job-dependencies"),
};

export { ApiRequestError };
