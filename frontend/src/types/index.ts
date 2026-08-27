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

export type TaskStatus = "PENDING" | "COMPLETED" | "DELEGATED" | "ESCALATED" | "CANCELLED";

export type Decision = "APPROVED" | "REJECTED";

export interface Task {
  id: string;
  instanceId: string;
  workflowId: string;
  workflowName: string;
  nodeId: string;
  nodeType: NodeType;
  nodeLabel: string | null;
  assignedToId: string;
  status: TaskStatus;
  /** The timeout deadline, or `null` when the node configures none. */
  dueAt: string | null;
  decision: Decision | null;
  comment: string | null;
  createdAt: string;
}

/** Mirrors the backend `InstanceStatus` enum. */
export type InstanceStatus = "RUNNING" | "COMPLETED" | "REJECTED" | "ERROR" | "CANCELLED";

export interface WorkflowInstance {
  id: string;
  workflowId: string;
  workflowName: string;
  workflowVersionId: string;
  versionNumber: number | null;
  initiatedById: string;
  initiatorName: string | null;
  status: InstanceStatus;
  currentNodeId: string | null;
  requestData: Record<string, unknown> | null;
  startedAt: string;
  completedAt: string | null;
}

export interface AuditEvent {
  id: string;
  actorId: string | null;
  action: string;
  entityType: string;
  entityId: string;
  createdAt: string;
}

export interface Dashboard {
  pendingTaskCount: number;
  pendingTasks: Task[];
  submittedInstances: WorkflowInstance[];
  recentActivity: AuditEvent[];
}

export interface PerformanceFilters {
  departmentId: string | null;
  workflowId: string | null;
  submittedFrom: string | null;
  submittedTo: string | null;
  minBottleneckSamples: number;
}

export interface NodePerformance {
  nodeId: string;
  nodeType: NodeType;
  nodeLabel: string | null;
  decidedTaskCount: number;
  averageDwellSeconds: number | null;
  bottleneck: boolean;
}

export interface WorkflowPerformance {
  workflowId: string;
  workflowName: string;
  filters: PerformanceFilters;
  totalInstanceVolume: number;
  runningInstanceCount: number;
  completedInstanceCount: number;
  rejectedInstanceCount: number;
  cancelledInstanceCount: number;
  erroredInstanceCount: number;
  decidedInstanceCount: number;
  averageApprovalTimeSeconds: number | null;
  rejectionRate: number | null;
  nodes: NodePerformance[];
  bottleneckNode: NodePerformance | null;
  bottleneckMinimumSamples: number;
}

export interface AuditLogEntry {
  id: string;
  actorId: string | null;
  action: string;
  entityType: string;
  entityId: string;
  beforeState: Record<string, unknown> | null;
  afterState: Record<string, unknown> | null;
  createdAt: string;
}

export interface AuditLogSearchPage {
  entries: AuditLogEntry[];
  totalCount: number;
  page: number;
  size: number;
}

export interface Attachment {
  id: string;
  instanceId: string;
  fileName: string;
  contentType: string;
  fileSize: number;
  uploadedById: string;
  createdAt: string;
}

export interface Comment {
  id: string;
  instanceId: string;
  authorId: string;
  authorName: string | null;
  body: string;
  parentId: string | null;
  createdAt: string;
}

export interface Delegation {
  id: string;
  delegatorId: string;
  delegateId: string;
  startAt: string;
  endAt: string;
  active: boolean;
  inEffectNow: boolean;
  reassignedTaskIds: string[];
}
