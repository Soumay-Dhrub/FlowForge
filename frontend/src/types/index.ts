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
