/** Typed wrappers for the `/api/audit-logs` endpoints. ADMIN only, both of them. */
import api, { unwrap } from "@/lib/api";
import { triggerBrowserDownload } from "@/lib/download";
import type { ApiResponse, AuditLogSearchPage } from "@/types";

/**
 * The search criteria of Requirement 19.3, as the filter form holds them.
 *
 * Empty strings rather than `undefined` for the unset case, so each field binds straight to a form
 * control; the blanks are dropped before the query string is built.
 *
 * `dateFrom`/`dateTo` are **calendar dates** (`2026-01-31`), which is what the endpoint parses. The
 * upper bound is inclusive server side — `dateTo=2026-01-31` covers the whole of the 31st — so the
 * client must not widen it into an instant.
 */
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

/**
 * `GET /api/audit-logs` — one page of the trail, newest first (Requirement 19.3).
 *
 * Paging and filtering are the server's job. The trail is the one table in the system that only ever
 * grows, so a client that fetched it all and filtered in the browser would get slower every day and
 * would be searching a subset the moment it was capped.
 *
 * A 400 means the date range is inverted (`dateFrom` after `dateTo`); a 403 means the caller is not
 * an ADMIN. Both are left as the original Axios error for the page to place.
 */
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

/**
 * `GET /api/audit-logs/export` — every matching entry as a CSV download (Requirement 19.4).
 *
 * Takes the same filters as the search and no page: an export exists to take everything that
 * matched. The response is `text/csv`, not the JSON envelope, so it is read as a blob and never
 * unwrapped — and it goes through the authenticated Axios instance, because a bare link would carry
 * no token and be answered 401.
 */
export async function exportAuditLogsCsv(
  filters: AuditLogFilters = EMPTY_AUDIT_LOG_FILTERS,
): Promise<void> {
  const response = await api.get<Blob>("/audit-logs/export", {
    params: filterParams(filters),
    responseType: "blob",
  });
  triggerBrowserDownload(response.data, "audit-logs.csv");
}

/**
 * Entity kinds the trail currently records, offered as suggestions.
 *
 * Suggestions rather than a closed list: the set grows with every audited entity, and a control that
 * only permitted today's values would make tomorrow's unsearchable.
 */
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
