export type JobState = "PENDING" | "RUNNING" | "SUCCEEDED" | "DEAD_LETTER" | "CANCELLED";

export interface JobResponse {
  id: number;
  idempotencyKey: string;
  jobType: string;
  payload: unknown;
  state: JobState;
  priority: number;
  attemptCount: number;
  maxAttempts: number;
  nextRunAt: string;
  claimedBy: string | null;
  leaseExpiresAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface JobExecutionResponse {
  id: number;
  attempt: number;
  workerId: string;
  startedAt: string;
  finishedAt: string | null;
  success: boolean | null;
  errorMessage: string | null;
}

export interface JobDetailResponse {
  job: JobResponse;
  executions: JobExecutionResponse[];
  dependsOn: number[];
}

export interface PagedJobs {
  content: JobResponse[];
  page: {
    size: number;
    number: number;
    totalElements: number;
    totalPages: number;
  };
}

export interface WorkerResponse {
  workerId: string;
  lastSeenAt: string | null;
}

export interface StatsResponse {
  countsByState: Record<string, number>;
  completedInLastFiveMinutes: number;
  latencyPercentilesNote: string;
}

export interface JobDependencyEdge {
  jobId: number;
  dependsOnId: number;
}

export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  details: string[];
}
