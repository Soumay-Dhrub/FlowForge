/** Typed wrappers for the `/api/workflows` endpoints. */
import axios from "axios";
import api, { unwrap } from "@/lib/api";
import type { ApiResponse, Workflow, WorkflowVersion } from "@/types";

/** Query keys, shared so a mutation invalidates exactly the caches the pages read. */
export const workflowKeys = {
  all: ["workflows"] as const,
  /** The list is keyed by its filter: each search term is its own cache entry. */
  list: (name: string) => ["workflows", "list", name] as const,
  detail: (id: string) => ["workflows", "detail", id] as const,
};

/**
 * `GET /api/workflows` — every workflow, newest first, optionally filtered by a name fragment.
 *
 * Filtering is delegated to the backend (`?name=`, case-insensitive `contains`) rather than done in
 * the browser: the client only ever holds the rows the server sent, so filtering here would silently
 * search a subset once the table is paged.
 *
 * List rows carry `versions: null` — use {@link fetchWorkflow} for the history.
 */
export async function fetchWorkflows(name = ""): Promise<Workflow[]> {
  const query = name.trim();
  return unwrap(
    await api.get<ApiResponse<Workflow[]>>("/workflows", {
      params: query ? { name: query } : undefined,
    }),
  );
}

/** `GET /api/workflows/{id}` — one workflow with its full version history (Requirement 8.3). */
export async function fetchWorkflow(id: string): Promise<Workflow> {
  return unwrap(await api.get<ApiResponse<Workflow>>(`/workflows/${id}`));
}

/**
 * `POST /api/workflows` — 201 with the new workflow and the blank draft version to author against.
 */
export async function createWorkflow(input: {
  name: string;
  description?: string;
}): Promise<Workflow> {
  return unwrap(
    await api.post<ApiResponse<Workflow>>("/workflows", {
      name: input.name,
      description: input.description?.trim() ? input.description : null,
    }),
  );
}

/**
 * `POST /api/workflows/{id}/clone` — 201 with a new DRAFT workflow deep-copying the source version's
 * nodes and edges (Requirements 8.1, 8.2).
 *
 * Omit `sourceVersionId` to copy the published version, falling back to the newest one when nothing
 * has been published yet.
 */
export async function cloneWorkflow(
  id: string,
  input: { sourceVersionId?: string; name?: string; description?: string } = {},
): Promise<Workflow> {
  return unwrap(await api.post<ApiResponse<Workflow>>(`/workflows/${id}/clone`, input));
}

/**
 * Raised when publishing is refused because the graph breaks the structural rules.
 *
 * The endpoint answers 422 listing *every* violation at once, so they are carried as a list: a
 * designer fixing one rule at a time would otherwise need one round trip per mistake.
 */
export class WorkflowPublishError extends Error {
  readonly violations: readonly string[];

  constructor(message: string, violations: readonly string[]) {
    super(message);
    this.name = "WorkflowPublishError";
    this.violations = violations;
  }
}

/**
 * `POST /api/workflows/{id}/versions/{versionId}/publish` — ADMIN only (403 for a MANAGER).
 *
 * Publishes the stored draft as an immutable snapshot and opens its successor draft. A 422 is
 * translated into a {@link WorkflowPublishError} carrying the violation list; every other failure
 * propagates as the original Axios error.
 */
export async function publishVersion(
  workflowId: string,
  versionId: string,
): Promise<WorkflowVersion> {
  try {
    return unwrap(
      await api.post<ApiResponse<WorkflowVersion>>(
        `/workflows/${workflowId}/versions/${versionId}/publish`,
      ),
    );
  } catch (error) {
    if (axios.isAxiosError(error) && error.response?.status === 422) {
      const body = error.response.data as ApiResponse<unknown> | undefined;
      const violations = (body?.errors ?? []).map((fieldError) => fieldError.message);
      throw new WorkflowPublishError(
        body?.message ?? "This workflow cannot be published yet.",
        violations,
      );
    }
    throw error;
  }
}

/** Human-readable label for a workflow's lifecycle status. */
export const WORKFLOW_STATUS_LABELS: Record<Workflow["status"], string> = {
  DRAFT: "Draft",
  ACTIVE: "Active",
  ARCHIVED: "Archived",
};

/**
 * Version history in the order a reader expects: newest version first.
 *
 * The backend returns the history ascending by version number; reversing here keeps the page's
 * "what happened most recently" reading order without a second request.
 */
export function versionsNewestFirst(workflow: Workflow): WorkflowVersion[] {
  return [...(workflow.versions ?? [])].sort((a, b) => b.versionNumber - a.versionNumber);
}
