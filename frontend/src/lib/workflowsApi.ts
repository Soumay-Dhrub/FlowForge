/** Typed wrappers for the `/api/workflows` endpoints. */
import axios from "axios";
import api, { unwrap } from "@/lib/api";
import type { ApiResponse, NodeType, Workflow, WorkflowVersion } from "@/types";

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
 * One node in a draft-save payload.
 *
 * `id` is a **payload-local correlation key**, not a database identifier: the server discards it and
 * assigns its own, and the edges in the same request name their endpoints with it. The canvas is
 * therefore free to mint ids client-side, but must re-seed itself from the response — the saved nodes
 * come back with *different* ids, and sending the old ones again would be rejected with 422.
 */
export interface SaveDraftNodeInput {
  id: string;
  type: NodeType;
  configJson: Record<string, unknown>;
  positionX: number;
  positionY: number;
}

/** One edge in a draft-save payload; endpoints name nodes in the same payload. */
export interface SaveDraftEdgeInput {
  id: null;
  sourceNodeId: string;
  targetNodeId: string;
  conditionExpr: string | null;
}

export interface SaveDraftInput {
  nodes: SaveDraftNodeInput[];
  edges: SaveDraftEdgeInput[];
}

/**
 * Raised when a draft save is refused because the payload cannot describe a coherent graph:
 * repeated node ids, or an edge naming a node that is not in the same payload.
 */
export class WorkflowDraftError extends Error {
  readonly violations: readonly string[];

  constructor(message: string, violations: readonly string[]) {
    super(message);
    this.name = "WorkflowDraftError";
    this.violations = violations;
  }
}

/**
 * `PUT /api/workflows/{id}/versions/{versionId}` — replace a draft version's graph (Requirements
 * 6.4, 6.5). ADMIN or MANAGER.
 *
 * The response is the *stored* version: its nodes and edges carry the server-assigned identifiers,
 * so callers must adopt them as the new canvas state rather than keeping the ids they sent.
 *
 * A 409 means the target version is published and therefore immutable — the editor should be aiming
 * at the draft. A 422 is translated into a {@link WorkflowDraftError} carrying the violation list.
 */
export async function saveDraft(
  workflowId: string,
  versionId: string,
  input: SaveDraftInput,
): Promise<WorkflowVersion> {
  try {
    return unwrap(
      await api.put<ApiResponse<WorkflowVersion>>(
        `/workflows/${workflowId}/versions/${versionId}`,
        input,
      ),
    );
  } catch (error) {
    if (axios.isAxiosError(error) && error.response?.status === 422) {
      const body = error.response.data as ApiResponse<unknown> | undefined;
      const violations = (body?.errors ?? []).map((fieldError) => fieldError.message);
      throw new WorkflowDraftError(
        body?.message ?? "This canvas could not be saved.",
        violations,
      );
    }
    throw error;
  }
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

/**
 * The version the builder may edit: the newest version that is not published.
 *
 * Published versions are immutable and a draft save aimed at one is refused with 409, so the editor
 * has to resolve its target rather than assume the current version. Normally exactly one draft
 * exists — a workflow is created with one, and publishing opens its successor — but `undefined` is a
 * real possibility the caller must handle rather than fall back to a published version.
 */
export function editableDraftVersion(workflow: Workflow | undefined): WorkflowVersion | undefined {
  if (!workflow) {
    return undefined;
  }
  return versionsNewestFirst(workflow).find((version) => !version.isPublished);
}
