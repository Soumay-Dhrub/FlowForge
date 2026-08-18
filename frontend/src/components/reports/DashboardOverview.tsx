"use client";

/**
 * The personal dashboard (Requirements 20.1, 20.2, 20.3).
 *
 * One request, three widgets: `GET /api/reports/dashboard` returns the caller's pending tasks, the
 * requests they submitted, and their recent activity together. Three separate queries would render
 * three independently-stale views of the same moment, and the endpoint exists precisely so a
 * dashboard is one snapshot.
 *
 * There is no user parameter to get wrong: the subject is whoever the token identifies, so this page
 * cannot be pointed at somebody else's queue.
 */
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { ClipboardList, History, Loader2, Send } from "lucide-react";
import { extractErrorMessage } from "@/lib/api";
import { formatAuditAction, formatDateTime } from "@/lib/format";
import { INSTANCE_STATUS_LABELS } from "@/lib/instancesApi";
import { fetchDashboard, reportKeys } from "@/lib/reportsApi";
import { TASK_STATUS_LABELS } from "@/lib/tasksApi";
import { useAuth } from "@/context/AuthContext";
import type { InstanceStatus, TaskStatus } from "@/types";

const TASK_STATUS_STYLES: Record<TaskStatus, string> = {
  PENDING: "bg-blue-100 text-blue-800",
  ESCALATED: "bg-amber-100 text-amber-900",
  DELEGATED: "bg-purple-100 text-purple-800",
  COMPLETED: "bg-green-100 text-green-800",
  CANCELLED: "bg-gray-200 text-gray-700",
};

const INSTANCE_STATUS_STYLES: Record<InstanceStatus, string> = {
  RUNNING: "bg-blue-100 text-blue-800",
  COMPLETED: "bg-green-100 text-green-800",
  REJECTED: "bg-red-100 text-red-800",
  ERROR: "bg-red-100 text-red-800",
  CANCELLED: "bg-gray-200 text-gray-700",
};

/** A titled panel, so the three widgets share one visual and heading structure. */
function Widget({
  title,
  icon: Icon,
  description,
  children,
}: {
  title: string;
  icon: typeof ClipboardList;
  description: string;
  children: React.ReactNode;
}) {
  return (
    <section aria-labelledby={`${title.toLowerCase().replace(/\s+/g, "-")}-heading`} className="mt-8">
      <div className="flex items-start gap-2">
        <Icon aria-hidden="true" className="mt-0.5 h-5 w-5 text-primary-600" />
        <div>
          <h2
            id={`${title.toLowerCase().replace(/\s+/g, "-")}-heading`}
            className="text-lg font-semibold text-gray-900"
          >
            {title}
          </h2>
          <p className="text-sm text-gray-600">{description}</p>
        </div>
      </div>
      <div className="mt-3">{children}</div>
    </section>
  );
}

export function DashboardOverview() {
  const { user } = useAuth();

  const dashboard = useQuery({
    queryKey: reportKeys.dashboard,
    queryFn: fetchDashboard,
  });

  if (dashboard.isPending) {
    return (
      <div className="mx-auto max-w-4xl">
        <h1 className="text-2xl font-bold text-primary-700">Dashboard</h1>
        <p role="status" className="mt-6 inline-flex items-center gap-2 text-sm text-gray-600">
          <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
          Loading your dashboard…
        </p>
      </div>
    );
  }

  if (dashboard.isError) {
    return (
      <div className="mx-auto max-w-4xl">
        <h1 className="text-2xl font-bold text-primary-700">Dashboard</h1>
        <p role="alert" className="mt-6 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
          {extractErrorMessage(dashboard.error, "Could not load your dashboard.")}
        </p>
        <button
          type="button"
          onClick={() => dashboard.refetch()}
          className="mt-3 rounded-md border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-primary-500"
        >
          Try again
        </button>
      </div>
    );
  }

  const { pendingTaskCount, pendingTasks, submittedInstances, recentActivity } = dashboard.data;

  return (
    <div className="mx-auto max-w-4xl">
      <h1 className="text-2xl font-bold text-primary-700">Dashboard</h1>
      <p className="mt-1 text-sm text-gray-600">
        {user?.name ? `${user.name} — your` : "Your"} workflow involvement in one view.
      </p>

      <Widget
        title="Pending tasks"
        icon={ClipboardList}
        description={
          pendingTaskCount === 1
            ? "1 task is waiting on you."
            : `${pendingTaskCount} tasks are waiting on you.`
        }
      >
        {pendingTasks.length === 0 ? (
          <p className="rounded-lg border border-gray-200 bg-white px-4 py-6 text-center text-sm text-gray-600">
            Nothing is waiting on you right now.
          </p>
        ) : (
          <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
            <table className="min-w-full divide-y divide-gray-200 text-sm">
              <caption className="sr-only">Tasks awaiting your action, newest first</caption>
              <thead className="bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
                <tr>
                  <th scope="col" className="px-4 py-3">
                    Stage
                  </th>
                  <th scope="col" className="px-4 py-3">
                    Workflow
                  </th>
                  <th scope="col" className="px-4 py-3">
                    Status
                  </th>
                  <th scope="col" className="px-4 py-3">
                    Raised
                  </th>
                  <th scope="col" className="px-4 py-3">
                    Due
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200">
                {pendingTasks.map((task) => (
                  <tr key={task.id} className="hover:bg-gray-50">
                    <th scope="row" className="px-4 py-3 text-left font-medium text-gray-900">
                      <Link
                        href={`/tasks/${task.id}`}
                        className="text-primary-700 hover:underline focus:outline-none focus:ring-2 focus:ring-primary-500"
                      >
                        {task.nodeLabel ?? task.nodeType}
                      </Link>
                    </th>
                    <td className="px-4 py-3 text-gray-700">{task.workflowName}</td>
                    <td className="px-4 py-3">
                      <span
                        className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium ${TASK_STATUS_STYLES[task.status]}`}
                      >
                        {TASK_STATUS_LABELS[task.status]}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-gray-700">{formatDateTime(task.createdAt)}</td>
                    <td className="px-4 py-3 text-gray-700">{formatDateTime(task.dueAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Widget>

      <Widget
        title="Submitted requests"
        icon={Send}
        description="Requests you started, with where each one has got to."
      >
        {submittedInstances.length === 0 ? (
          <p className="rounded-lg border border-gray-200 bg-white px-4 py-6 text-center text-sm text-gray-600">
            You have not submitted any requests yet.
          </p>
        ) : (
          <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
            <table className="min-w-full divide-y divide-gray-200 text-sm">
              <caption className="sr-only">Requests you submitted, newest first</caption>
              <thead className="bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
                <tr>
                  <th scope="col" className="px-4 py-3">
                    Workflow
                  </th>
                  <th scope="col" className="px-4 py-3">
                    Status
                  </th>
                  <th scope="col" className="px-4 py-3">
                    Submitted
                  </th>
                  <th scope="col" className="px-4 py-3">
                    Finished
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200">
                {submittedInstances.map((instance) => (
                  <tr key={instance.id} className="hover:bg-gray-50">
                    <th scope="row" className="px-4 py-3 text-left font-medium text-gray-900">
                      {instance.workflowName}
                      {instance.versionNumber === null ? null : (
                        <span className="block text-xs font-normal text-gray-500">
                          Version {instance.versionNumber}
                        </span>
                      )}
                    </th>
                    <td className="px-4 py-3">
                      <span
                        className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium ${INSTANCE_STATUS_STYLES[instance.status]}`}
                      >
                        {INSTANCE_STATUS_LABELS[instance.status]}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-gray-700">{formatDateTime(instance.startedAt)}</td>
                    <td className="px-4 py-3 text-gray-700">
                      {formatDateTime(instance.completedAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Widget>

      <Widget
        title="Recent activity"
        icon={History}
        description="Your last 20 recorded actions, newest first."
      >
        {recentActivity.length === 0 ? (
          <p className="rounded-lg border border-gray-200 bg-white px-4 py-6 text-center text-sm text-gray-600">
            No recorded activity yet.
          </p>
        ) : (
          <ol className="divide-y divide-gray-200 rounded-lg border border-gray-200 bg-white">
            {recentActivity.map((event) => (
              <li key={event.id} className="flex flex-wrap items-baseline justify-between gap-2 px-4 py-3">
                <span className="text-sm font-medium text-gray-900">
                  {formatAuditAction(event.action)}
                  <span className="ml-2 text-xs font-normal text-gray-500">{event.entityType}</span>
                </span>
                <time dateTime={event.createdAt} className="text-xs text-gray-600">
                  {formatDateTime(event.createdAt)}
                </time>
              </li>
            ))}
          </ol>
        )}
      </Widget>
    </div>
  );
}

export default DashboardOverview;
