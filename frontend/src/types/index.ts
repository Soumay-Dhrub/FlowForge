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

export interface Workflow {
  id: string;
  name: string;
  description?: string;
  status: "DRAFT" | "ACTIVE" | "ARCHIVED";
  createdBy: string;
  createdAt: string;
}

export type NodeType = "START" | "TASK" | "APPROVAL" | "CONDITION" | "NOTIFICATION" | "END";

export interface WorkflowNode {
  id: string;
  type: NodeType;
  positionX: number;
  positionY: number;
  config: Record<string, unknown>;
}

export interface WorkflowEdge {
  id: string;
  sourceNodeId: string;
  targetNodeId: string;
  condition?: string;
}
