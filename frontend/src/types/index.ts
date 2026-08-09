// Shared TypeScript types for FlowForge

export interface ApiResponse<T> {
  success: boolean;
  message?: string;
  data?: T;
  errors?: Array<{ field: string; message: string }>;
}

export interface User {
  id: string;
  name: string;
  email: string;
  role: "ADMIN" | "MANAGER" | "EMPLOYEE";
  departmentId?: string;
  isActive: boolean;
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
