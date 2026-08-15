/** Typed wrappers for the `/api/tasks` endpoints. */
import api, { unwrap } from "@/lib/api";
import type { ApiResponse, Decision, Task, TaskStatus } from "@/types";

/**
 * The filters `GET /api/tasks` understands, as the UI holds them.
 *
 * Empty strings rather than `undefined` for the unset case: these are bound straight to form
 * controls, and "no status chosen" is the `<select>`'s empty option. {@link listTasks} drops the
 * blanks so an untouched filter never reaches the query string.
 */
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

/**
 * `GET /api/tasks` — the caller's tasks, newest first, narrowed by every supplied filter
 * (Requirements 12.1, 12.2, 12.3).
 *
 * Filtering is the server's job, as it is for the workflow table: the client only holds the rows the
 * server sent, so filtering here would quietly search a subset.
 *
 * The endpoint also accepts `assignedTo` so a privileged role can read another queue, or `all`. That
 * is not surfaced yet — this page is a personal queue — and an unprivileged caller passing it is
 * silently scoped back to themselves anyway.
 */
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

/**
 * `PATCH /api/tasks/{id}/decision` — record a decision and resume the instance (Requirements 13.1,
 * 13.2).
 *
 * Failures are left as the original Axios error so callers can place them on the right control:
 *  - 400 — rejecting with a blank comment (Requirement 13.2); belongs on the comment field;
 *  - 403 — the task is not the caller's (Requirement 13.4). Holding ADMIN does not override this:
 *    an approval has to be attributable to whoever actually made it;
 *  - 409 — the task has already been decided, or is otherwise no longer open.
 */
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

/**
 * True when this caller can still record a decision on this task.
 *
 * Mirrors the service's own rule exactly — the task must be `PENDING` or `ESCALATED` *and* assigned
 * to the caller — so the form is only offered where the request would actually succeed. Guessing
 * wider here would put a form in front of someone whose submit is answered 403 or 409.
 */
export function isDecidableBy(task: Task, userId: string | undefined): boolean {
  if (!userId || task.assignedToId !== userId) {
    return false;
  }
  return task.status === "PENDING" || task.status === "ESCALATED";
}

/**
 * A `<input type="date">` value as the ISO instant that starts that day, or `""` for no bound.
 *
 * The date is read in the reader's own zone — `new Date("2026-01-02T00:00:00")` is local midnight —
 * because "raised on the 2nd" means their 2nd, not UTC's.
 */
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
