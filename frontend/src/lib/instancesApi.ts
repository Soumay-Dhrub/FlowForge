/** Typed wrappers for the `/api/instances` endpoints. */
import api, { unwrap } from "@/lib/api";
import type { ApiResponse, InstanceStatus, WorkflowInstance } from "@/types";

/** Query keys, shared so a task decision invalidates the instance it advanced. */
export const instanceKeys = {
  all: ["instances"] as const,
  detail: (id: string) => ["instances", "detail", id] as const,
};

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
