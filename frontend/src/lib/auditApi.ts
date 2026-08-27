/** Typed wrappers for the `/api/audit-logs` endpoints. ADMIN only, both of them. */
import api, { unwrap } from "@/lib/api";
import { triggerBrowserDownload } from "@/lib/download";
import type { ApiResponse, AuditLogSearchPage } from "@/types";

export interface AuditLogFilters {
  /** Entries this user performed; a UUID, so the UI offers a picker rather than a text box. */
  userId: string;
  /** Entity kind, e.g. `Task`. Matched case-insensitively, but for equality, not as a substring. */
  entityType: string;
  /** Action code, e.g. `APPROVE_TASK`. Also matched case-insensitively for equality. */
  action: string;
  dateFrom: string;
  dateTo: string;
}

export const EMPTY_AUDIT_LOG_FILTERS: AuditLogFilters = {
  userId: "",
  entityType: "",
  action: "",
  dateFrom: "",
  dateTo: "",
};

/** How many entries one page of the viewer shows. */
export const AUDIT_PAGE_SIZE = 25;

/** Query keys, keyed by the whole filter set and the page: each view is its own cache entry. */
export const auditLogKeys = {
  all: ["audit-logs"] as const,
  search: (filters: AuditLogFilters, page: number) =>
    [
      "audit-logs",
      "search",
      filters.userId,
      filters.entityType,
      filters.action,
      filters.dateFrom,
      filters.dateTo,
      page,
    ] as const,
};

/** The supplied filters as query parameters, with the untouched ones omitted entirely. */
function filterParams(filters: AuditLogFilters): Record<string, string> {
  const params: Record<string, string> = {};
  if (filters.userId) {
    params.userId = filters.userId;
  }
  if (filters.entityType) {
    params.entityType = filters.entityType;
  }
  if (filters.action) {
    params.action = filters.action;
  }
  if (filters.dateFrom) {
    params.dateFrom = filters.dateFrom;
  }
  if (filters.dateTo) {
    params.dateTo = filters.dateTo;
  }
  return params;
}

export async function searchAuditLogs(
  filters: AuditLogFilters = EMPTY_AUDIT_LOG_FILTERS,
  page = 0,
  size: number = AUDIT_PAGE_SIZE,
): Promise<AuditLogSearchPage> {
  return unwrap(
    await api.get<ApiResponse<AuditLogSearchPage>>("/audit-logs", {
      params: { ...filterParams(filters), page, size },
    }),
  );
}

export async function exportAuditLogsCsv(
  filters: AuditLogFilters = EMPTY_AUDIT_LOG_FILTERS,
): Promise<void> {
  const response = await api.get<Blob>("/audit-logs/export", {
    params: filterParams(filters),
    responseType: "blob",
  });
  triggerBrowserDownload(response.data, "audit-logs.csv");
}

export const AUDIT_ENTITY_TYPES: readonly string[] = [
  "Attachment",
  "Comment",
  "Notification",
  "NotificationPreference",
  "Task",
  "User",
  "Workflow",
  "WorkflowInstance",
  "WorkflowVersion",
];

/** Action codes the trail currently records, offered as suggestions for the same reason. */
export const AUDIT_ACTIONS: readonly string[] = [
  "APPROVE_TASK",
  "CANCEL_INSTANCE",
  "CREATE_INSTANCE",
  "CREATE_TASK",
  "CREATE_USER",
  "CREATE_WORKFLOW",
  "ESCALATE_TASK",
  "INSTANCE_COMPLETED",
  "PASSWORD_RESET",
  "POST_COMMENT",
  "PUBLISH_VERSION",
  "REJECT_TASK",
  "SAVE_DRAFT",
  "STATUS_CHANGE",
  "UPDATE_NOTIFICATION_PREFERENCE",
  "UPDATE_USER",
  "UPLOAD_ATTACHMENT",
];
