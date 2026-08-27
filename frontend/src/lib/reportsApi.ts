/** Typed wrappers for the `/api/reports` endpoints. */
import api, { unwrap } from "@/lib/api";
import { downloadAsJson, triggerBrowserDownload } from "@/lib/download";
import type { ApiResponse, Dashboard, WorkflowPerformance } from "@/types";

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

export async function fetchDashboard(): Promise<Dashboard> {
  return unwrap(await api.get<ApiResponse<Dashboard>>("/reports/dashboard"));
}

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

export function exportPerformanceJson(report: WorkflowPerformance): void {
  downloadAsJson(report, `workflow-${report.workflowId}-performance.json`);
}
