/** Typed wrappers for the `/api/instances` endpoints. */
import api, { unwrap } from "@/lib/api";
import type { ApiResponse, InstanceStatus, WorkflowInstance } from "@/types";

/** Query keys, shared so a task decision invalidates the instance it advanced. */
export const instanceKeys = {
  all: ["instances"] as const,
  detail: (id: string) => ["instances", "detail", id] as const,
};

/**
 * `GET /api/instances/{id}` — one request in full, submitted payload included.
 *
 * Restricted to the initiator or a privileged role, so an EMPLOYEE deciding a task on someone else's
 * request gets a 403 here even though they can read the task itself. Callers must treat that as a
 * missing section rather than a failed page.
 */
export async function fetchInstance(id: string): Promise<WorkflowInstance> {
  return unwrap(await api.get<ApiResponse<WorkflowInstance>>(`/instances/${id}`));
}

export const INSTANCE_STATUS_LABELS: Record<InstanceStatus, string> = {
  RUNNING: "Running",
  COMPLETED: "Completed",
  REJECTED: "Rejected",
  ERROR: "Error",
  CANCELLED: "Cancelled",
};
