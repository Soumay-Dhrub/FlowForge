"use client";

/**
 * The way in to the per-workflow reports (Requirement 21.1). ADMIN and MANAGER only.
 *
 * The performance endpoint reports on one workflow at a time, so a report URL needs a workflow id
 * from somewhere. This is that somewhere: without it the analytics pages would only be reachable by
 * typing a UUID.
 */
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { BarChart3, Loader2 } from "lucide-react";
import { extractErrorMessage, isForbiddenError } from "@/lib/api";
import { WORKFLOW_STATUS_LABELS, fetchWorkflows, workflowKeys } from "@/lib/workflowsApi";
import { useAuth } from "@/context/AuthContext";
import NotAuthorized from "@/components/ui/NotAuthorized";

const REPORTS_ROLE_MESSAGE = "Only administrators and managers can view workflow analytics.";

export function ReportsIndex() {
  const { user, status } = useAuth();
  const canView = user?.roleName === "ADMIN" || user?.roleName === "MANAGER";

  const workflows = useQuery({
    queryKey: workflowKeys.list(""),
    queryFn: () => fetchWorkflows(),
    enabled: canView,
  });

  if (status === "authenticated" && !canView) {
    return <NotAuthorized message={REPORTS_ROLE_MESSAGE} />;
  }
  if (workflows.isError && isForbiddenError(workflows.error)) {
    return <NotAuthorized message={REPORTS_ROLE_MESSAGE} />;
  }

  const rows = workflows.data ?? [];

  return (
    <div className="mx-auto max-w-3xl">
      <h1 className="text-2xl font-bold text-primary-700">Reports</h1>
      <p className="mt-1 text-sm text-gray-600">
        Pick a workflow to see its approval time, rejection rate, volume and bottleneck stage.
      </p>

      {workflows.isPending ? (
        <p role="status" className="mt-6 inline-flex items-center gap-2 text-sm text-gray-600">
          <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
          Loading workflows…
        </p>
      ) : null}

      {workflows.isError ? (
        <p role="alert" className="mt-6 rounded-md bg-danger-50 px-3 py-2 text-sm text-danger-700">
          {extractErrorMessage(workflows.error, "Could not load workflows.")}
        </p>
      ) : null}

      {workflows.isSuccess && rows.length === 0 ? (
        <p className="mt-6 rounded-xl border border-gray-200 bg-white px-4 py-6 text-center text-sm text-gray-600">
          No workflows exist yet, so there is nothing to measure.
        </p>
      ) : null}

      {rows.length > 0 ? (
        <ul className="mt-6 divide-y divide-gray-200 rounded-xl border border-gray-200 bg-white">
          {rows.map((workflow) => (
            <li key={workflow.id} className="flex items-center justify-between gap-3 px-4 py-3">
              <span className="text-sm font-medium text-gray-900">
                {workflow.name}
                <span className="ml-2 text-xs font-normal text-gray-500">
                  {WORKFLOW_STATUS_LABELS[workflow.status]}
                </span>
              </span>
              <Link
                href={`/reports/${workflow.id}`}
                className="inline-flex items-center gap-2 rounded-md border border-gray-300 px-2.5 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50"
              >
                <BarChart3 aria-hidden="true" className="h-3.5 w-3.5" />
                View report
                <span className="sr-only"> for {workflow.name}</span>
              </Link>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

export default ReportsIndex;
