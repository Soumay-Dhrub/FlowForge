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

export async function cloneWorkflow(
  id: string,
  input: { sourceVersionId?: string; name?: string; description?: string } = {},
): Promise<Workflow> {
  return unwrap(await api.post<ApiResponse<Workflow>>(`/workflows/${id}/clone`, input));
}

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

export class WorkflowPublishError extends Error {
  readonly violations: readonly string[];

  constructor(message: string, violations: readonly string[]) {
    super(message);
    this.name = "WorkflowPublishError";
    this.violations = violations;
  }
}

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

export function versionsNewestFirst(workflow: Workflow): WorkflowVersion[] {
  return [...(workflow.versions ?? [])].sort((a, b) => b.versionNumber - a.versionNumber);
}

export function editableDraftVersion(workflow: Workflow | undefined): WorkflowVersion | undefined {
  if (!workflow) {
    return undefined;
  }
  return versionsNewestFirst(workflow).find((version) => !version.isPublished);
}
