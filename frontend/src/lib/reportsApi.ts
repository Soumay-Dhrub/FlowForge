/** Typed wrappers for the `/api/reports` endpoints. */
import api, { unwrap } from "@/lib/api";
import { downloadAsJson, triggerBrowserDownload } from "@/lib/download";
import type { ApiResponse, Dashboard, WorkflowPerformance } from "@/types";

/**
 * The narrowings the performance report accepts, as the filter form holds them
 * (Requirement 21.4).
 *
 * Empty strings rather than `undefined` for the unset case: these are bound straight to form
 * controls, and "no department chosen" is the `<select>`'s empty option. The blanks are dropped
 * before the query string is built, so an untouched filter never reaches the server.
 *
 * `dateFrom`/`dateTo` are **calendar dates** (`2026-01-31`), exactly what `<input type="date">`
 * produces. The endpoint parses them as `LocalDate` and answers 400 for a full instant, so they must
 * not be widened into `toISOString()` values the way the task filters are.
 */
export interface PerformanceFilterInput {
  departmentId: string;
  dateFrom: string;
  dateTo: string;
  /** Decided tasks a node needs before it may be named the bottleneck; blank uses the default. */
  minSamples: string;
}

export const EMPTY_PERFORMANCE_FILTERS: PerformanceFilterInput = {
  departmentId: "",
  dateFrom: "",
  dateTo: "",
  minSamples: "",
};

/** Query keys, shared so a decision elsewhere can invalidate exactly the reports that moved. */
export const reportKeys = {
  all: ["reports"] as const,
  dashboard: ["reports", "dashboard"] as const,
  /** Keyed by the whole filter set: each combination is its own cache entry. */
  performance: (workflowId: string, filters: PerformanceFilterInput) =>
    [
      "reports",
      "performance",
      workflowId,
      filters.departmentId,
      filters.dateFrom,
      filters.dateTo,
      filters.minSamples,
    ] as const,
};

/** The supplied filters as query parameters, with the untouched ones omitted entirely. */
function performanceParams(filters: PerformanceFilterInput): Record<string, string> {
  const params: Record<string, string> = {};
  if (filters.departmentId) {
    params.department = filters.departmentId;
  }
  if (filters.dateFrom) {
    params.dateFrom = filters.dateFrom;
  }
  if (filters.dateTo) {
    params.dateTo = filters.dateTo;
  }
  if (filters.minSamples) {
    params.minSamples = filters.minSamples;
  }
  return params;
}

/**
 * `GET /api/reports/dashboard` — the caller's own pending tasks, submitted requests and recent
 * activity (Requirements 20.1, 20.2, 20.3).
 *
 * Takes no user parameter: the subject is whoever the token identifies, so there is no request that
 * returns somebody else's dashboard. Open to every authenticated role.
 */
export async function fetchDashboard(): Promise<Dashboard> {
  return unwrap(await api.get<ApiResponse<Dashboard>>("/reports/dashboard"));
}

/**
 * `GET /api/reports/workflow/{id}/performance` — aggregate metrics for one workflow
 * (Requirements 21.1–21.4). ADMIN or MANAGER; anything else is answered 403.
 *
 * `averageApprovalTimeSeconds` and `rejectionRate` arrive as `null` when no instance qualified.
 * Callers must render that as "no data" — coercing it to zero would report instantaneous approvals
 * and a spotless rejection record for a population that is empty.
 */
export async function fetchWorkflowPerformance(
  workflowId: string,
  filters: PerformanceFilterInput = EMPTY_PERFORMANCE_FILTERS,
): Promise<WorkflowPerformance> {
  const params = performanceParams(filters);
  return unwrap(
    await api.get<ApiResponse<WorkflowPerformance>>(`/reports/workflow/${workflowId}/performance`, {
      params: Object.keys(params).length > 0 ? params : undefined,
    }),
  );
}

/**
 * `GET /api/reports/workflow/{id}/performance?format=csv` — the same report as a download
 * (Requirement 21.5).
 *
 * `format=csv` selects a different handler server side that returns `text/csv`, **not** the JSON
 * envelope, so the body is taken as a blob and never unwrapped or parsed. It still has to travel
 * through the authenticated Axios instance: a bare link would carry no token and be refused.
 */
export async function exportPerformanceCsv(
  workflowId: string,
  filters: PerformanceFilterInput = EMPTY_PERFORMANCE_FILTERS,
): Promise<void> {
  const response = await api.get<Blob>(`/reports/workflow/${workflowId}/performance`, {
    params: { ...performanceParams(filters), format: "csv" },
    responseType: "blob",
  });
  triggerBrowserDownload(response.data, `workflow-${workflowId}-performance.csv`);
}

/**
 * The JSON export of Requirement 21.5, taken from the report already on screen.
 *
 * `?format=json` returns the same numbers this page is rendering, so re-requesting them would only
 * add a round trip and a window in which the file could disagree with the view. What is downloaded
 * is exactly what was read.
 */
export function exportPerformanceJson(report: WorkflowPerformance): void {
  downloadAsJson(report, `workflow-${report.workflowId}-performance.json`);
}
