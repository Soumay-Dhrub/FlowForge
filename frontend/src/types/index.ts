// Shared TypeScript types for FlowForge

export interface ApiResponse<T> {
  success: boolean;
  message?: string;
  data?: T;
  errors?: Array<{ field: string; message: string }>;
}

export type RoleName = "ADMIN" | "MANAGER" | "EMPLOYEE";

/** Mirrors the backend `UserResponse` returned by `GET /api/users/me`. */
export interface User {
  id: string;
  name: string;
  email: string;
  roleId: string;
  roleName: RoleName;
  departmentId: string | null;
  departmentName: string | null;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

/** Mirrors the backend `TokenResponse` returned by login and refresh. */
export interface TokenPair {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
}

/**
 * Mirrors the backend `NotificationResponse` returned by `GET /api/notifications`.
 *
 * `eventType` is a plain string rather than a union: the column is a free-form `VARCHAR(50)` and a
 * workflow's Notification node may emit a designer-defined type, so the UI must render one it has
 * never seen. {@link NOTIFICATION_EVENT_LABELS} maps the ones the platform raises itself.
 */
export interface Notification {
  id: string;
  eventType: string;
  payload: Record<string, unknown>;
  isRead: boolean;
  createdAt: string;
}

/** Options for the role selector, from `GET /api/roles`. */
export interface RoleOption {
  id: string;
  name: string;
}

/** Options for the department selector, from `GET /api/departments`. */
export interface DepartmentOption {
  id: string;
  name: string;
}

export type WorkflowStatus = "DRAFT" | "ACTIVE" | "ARCHIVED";

/**
 * Mirrors the backend `WorkflowResponse`.
 *
 * `versions` is `null` on list responses and populated only by `GET /api/workflows/{id}`, so the
 * version history is not loaded for every row of the workflow table (Requirement 8.3).
 */
export interface Workflow {
  id: string;
  name: string;
  description: string | null;
  status: WorkflowStatus;
  createdById: string;
  createdByName: string | null;
  createdAt: string;
  updatedAt: string;
  versions: WorkflowVersion[] | null;
}

/**
 * Mirrors the backend `NodeType` enum, including `AND_JOIN`.
 *
 * The synchronisation node is part of the set the engine executes and the builder must be able to
 * place it: without it a parallel workflow can be drawn but never joined (Requirement 10.2).
 */
export type NodeType =
  | "START"
  | "TASK"
  | "APPROVAL"
  | "CONDITION"
  | "NOTIFICATION"
  | "AND_JOIN"
  | "END";

/** Mirrors the backend `WorkflowVersionResponse`. */
export interface WorkflowVersion {
  id: string;
  workflowId: string;
  versionNumber: number;
  graphJson: Record<string, unknown> | null;
  isPublished: boolean;
  isCurrent: boolean;
  publishedAt: string | null;
  publishedById: string | null;
  publishedByName: string | null;
  createdAt: string;
  updatedAt: string;
  nodes: WorkflowNode[] | null;
  edges: WorkflowEdge[] | null;
}

/** Mirrors the backend `WorkflowNodeResponse`. */
export interface WorkflowNode {
  id: string;
  versionId: string;
  type: NodeType;
  configJson: Record<string, unknown> | null;
  positionX: number | null;
  positionY: number | null;
}

/** Mirrors the backend `WorkflowEdgeResponse`. */
export interface WorkflowEdge {
  id: string;
  versionId: string;
  sourceNodeId: string;
  targetNodeId: string;
  conditionExpr: string | null;
}
