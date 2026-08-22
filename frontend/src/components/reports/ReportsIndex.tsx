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
import { AlertCircle, BarChart3 } from "lucide-react";
import { extractErrorMessage, isForbiddenError } from "@/lib/api";
import { WORKFLOW_STATUS_LABELS, fetchWorkflows, workflowKeys } from "@/lib/workflowsApi";
import { useAuth } from "@/context/AuthContext";
import Badge, { type BadgeTone } from "@/components/ui/Badge";
import Card from "@/components/ui/Card";
import EmptyState from "@/components/ui/EmptyState";
import NotAuthorized from "@/components/ui/NotAuthorized";
import PageHeader from "@/components/ui/PageHeader";
import { SkeletonBar } from "@/components/ui/Skeleton";
import type { Workflow } from "@/types";

const REPORTS_ROLE_MESSAGE = "Only administrators and managers can view workflow analytics.";

/** Same reading as the workflow table, so a definition does not change colour between screens. */
const STATUS_TONES: Record<Workflow["status"], BadgeTone> = {
  DRAFT: "neutral",
  ACTIVE: "success",
  ARCHIVED: "warning",
};

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
      <PageHeader
        title="Reports"
        description="Pick a workflow to see its approval time, rejection rate, volume and bottleneck stage."
      />

      {workflows.isPending ? (
        <div role="status" className="space-y-2">
          <span className="sr-only">Loading workflows</span>
          <SkeletonBar className="h-14 w-full rounded-xl" />
          <SkeletonBar className="h-14 w-full rounded-xl" />
          <SkeletonBar className="h-14 w-full rounded-xl" />
        </div>
      ) : null}

      {workflows.isError ? (
        <p
          role="alert"
          className="flex items-start gap-2 rounded-xl border border-danger-200 bg-danger-50 px-3.5 py-2.5 text-sm text-danger-800"
        >
          <AlertCircle aria-hidden className="mt-0.5 h-4 w-4 shrink-0 text-danger-600" />
          {extractErrorMessage(workflows.error, "Could not load workflows.")}
        </p>
      ) : null}

      {workflows.isSuccess && rows.length === 0 ? (
        <Card padded={false}>
          <EmptyState
            filtered={false}
            icon={BarChart3}
            title="No workflows exist yet, so there is nothing to measure."
            description="Once a workflow is published and requests start flowing through it, its numbers appear here."
          />
        </Card>
      ) : null}

      {rows.length > 0 ? (
        <Card padded={false}>
          <ul className="divide-y divide-gray-100">
            {rows.map((workflow) => (
              <li
                key={workflow.id}
                className="flex items-center justify-between gap-3 px-4 py-3 transition-colors hover:bg-gray-50/80"
              >
                <span className="flex min-w-0 items-center gap-2.5">
                  <span className="truncate text-sm font-medium text-gray-900">{workflow.name}</span>
                  <Badge tone={STATUS_TONES[workflow.status]}>
                    {WORKFLOW_STATUS_LABELS[workflow.status]}
                  </Badge>
                </span>
                {/*
                  A link, not a button: it goes somewhere, so it should be openable in a new tab. The
                  workflow name is appended for screen readers because "View report" alone, repeated down
                  the list, does not say which report.
                */}
                <Link
                  href={`/reports/${workflow.id}`}
                  className="inline-flex h-8 shrink-0 items-center gap-1.5 rounded-md border border-gray-300 bg-white px-3 text-xs font-medium text-gray-700 shadow-xs transition-colors hover:bg-gray-50 hover:text-gray-900"
                >
                  <BarChart3 aria-hidden className="h-3.5 w-3.5" />
                  View report
                  <span className="sr-only"> for {workflow.name}</span>
                </Link>
              </li>
            ))}
          </ul>
        </Card>
      ) : null}
    </div>
  );
}

export default ReportsIndex;
