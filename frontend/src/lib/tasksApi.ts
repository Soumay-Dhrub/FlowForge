/** Typed wrappers for the `/api/tasks` endpoints. */
import api, { unwrap } from "@/lib/api";
import type { ApiResponse, Decision, Task, TaskStatus } from "@/types";

export interface TaskFilters {
  status: TaskStatus | "";
  workflowId: string;
  /** Inclusive lower bound as an ISO-8601 instant. */
  createdFrom: string;
  /** Inclusive upper bound as an ISO-8601 instant. */
  createdTo: string;
}

export const EMPTY_TASK_FILTERS: TaskFilters = {
  status: "",
  workflowId: "",
  createdFrom: "",
  createdTo: "",
};

/** Query keys, shared so a decision invalidates exactly the caches the pages read. */
export const taskKeys = {
  all: ["tasks"] as const,
  /** Keyed by the whole filter set: each combination is its own cache entry. */
  list: (filters: TaskFilters) =>
    ["tasks", "list", filters.status, filters.workflowId, filters.createdFrom, filters.createdTo] as const,
  detail: (id: string) => ["tasks", "detail", id] as const,
};

export async function listTasks(filters: TaskFilters = EMPTY_TASK_FILTERS): Promise<Task[]> {
  const params: Record<string, string> = {};
  if (filters.status) {
    params.status = filters.status;
  }
  if (filters.workflowId) {
    params.workflowId = filters.workflowId;
  }
  if (filters.createdFrom) {
    params.createdFrom = filters.createdFrom;
  }
  if (filters.createdTo) {
    params.createdTo = filters.createdTo;
  }
  return unwrap(
    await api.get<ApiResponse<Task[]>>("/tasks", {
      params: Object.keys(params).length > 0 ? params : undefined,
    }),
  );
}

/** `GET /api/tasks/{id}` — one task. Open to any authenticated caller who knows the id. */
export async function getTask(id: string): Promise<Task> {
  return unwrap(await api.get<ApiResponse<Task>>(`/tasks/${id}`));
}

export async function recordDecision(
  id: string,
  decision: Decision,
  comment: string | null,
): Promise<Task> {
  return unwrap(
    await api.patch<ApiResponse<Task>>(`/tasks/${id}/decision`, {
      decision,
      comment: comment?.trim() ? comment.trim() : null,
    }),
  );
}

/** The statuses a task can hold, in lifecycle order, for the filter control. */
export const TASK_STATUSES: readonly TaskStatus[] = [
  "PENDING",
  "ESCALATED",
  "DELEGATED",
  "COMPLETED",
  "CANCELLED",
];

export const TASK_STATUS_LABELS: Record<TaskStatus, string> = {
  PENDING: "Pending",
  COMPLETED: "Completed",
  DELEGATED: "Delegated",
  ESCALATED: "Escalated",
  CANCELLED: "Cancelled",
};

export const DECISION_LABELS: Record<Decision, string> = {
  APPROVED: "Approved",
  REJECTED: "Rejected",
};

export function isDecidableBy(task: Task, userId: string | undefined): boolean {
  if (!userId || task.assignedToId !== userId) {
    return false;
  }
  return task.status === "PENDING" || task.status === "ESCALATED";
}

export function startOfDayInstant(date: string): string {
  if (!date) {
    return "";
  }
  const parsed = new Date(`${date}T00:00:00`);
  return Number.isNaN(parsed.getTime()) ? "" : parsed.toISOString();
}

/** A `<input type="date">` value as the ISO instant that ends that day, so the bound is inclusive. */
export function endOfDayInstant(date: string): string {
  if (!date) {
    return "";
  }
  const parsed = new Date(`${date}T23:59:59.999`);
  return Number.isNaN(parsed.getTime()) ? "" : parsed.toISOString();
}
