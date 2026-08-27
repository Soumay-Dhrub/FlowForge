"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { ClipboardList, History, Send, type LucideIcon } from "lucide-react";
import { extractErrorMessage } from "@/lib/api";
import { formatAuditAction, formatDateTime } from "@/lib/format";
import { INSTANCE_STATUS_LABELS } from "@/lib/instancesApi";
import { fetchDashboard, reportKeys } from "@/lib/reportsApi";
import { TASK_STATUS_LABELS } from "@/lib/tasksApi";
import { useAuth } from "@/context/AuthContext";
import Badge, { INSTANCE_STATUS_TONES, TASK_STATUS_TONES } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import Card, { StatCard } from "@/components/ui/Card";
import PageHeader from "@/components/ui/PageHeader";
import { SkeletonCard } from "@/components/ui/Skeleton";

function Widget({
  title,
  icon: Icon,
  description,
  children,
}: {
  title: string;
  icon: LucideIcon;
  description: string;
  children: React.ReactNode;
}) {
  const headingId = `${title.toLowerCase().replace(/\s+/g, "-")}-heading`;
  return (
    <section aria-labelledby={headingId} className="mt-8">
      <div className="flex items-start gap-3">
        <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-primary-50 text-primary-600">
          <Icon aria-hidden="true" className="h-4 w-4" />
        </span>
        <div className="min-w-0">
          <h2 id={headingId} className="text-base font-semibold text-gray-900">
            {title}
          </h2>
          <p className="text-sm text-gray-500">{description}</p>
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

  // The greeting is the same in every state, so the header is rendered once rather than repeated in each
  // branch — which is how the three copies of it drifted apart.
  const header = (
    <PageHeader
      title={user?.name ? `Welcome back, ${user.name.split(" ")[0]}` : "Dashboard"}
      description="Your workflow involvement in one view: what is waiting on you, what you have submitted, and what has happened recently."
    />
  );

  if (dashboard.isPending) {
    return (
      <div>
        {header}
        <div className="grid gap-4 sm:grid-cols-3">
          <SkeletonCard label="Loading your dashboard" lines={2} />
          <SkeletonCard label="Loading" lines={2} />
          <SkeletonCard label="Loading" lines={2} />
        </div>
      </div>
    );
  }

  if (dashboard.isError) {
    return (
      <div>
        {header}
        <Card>
          <p role="alert" className="text-sm text-danger-700">
            {extractErrorMessage(dashboard.error, "Could not load your dashboard.")}
          </p>
          <Button variant="secondary" size="sm" className="mt-3" onClick={() => dashboard.refetch()}>
            Try again
          </Button>
        </Card>
      </div>
    );
  }

  const { pendingTaskCount, pendingTasks, submittedInstances, recentActivity } = dashboard.data;

  return (
    <div>
      {header}

      {/* The three figures up front, so the answer to "is anything waiting on me" is available without
          reading a table. */}
      <div className="grid gap-4 sm:grid-cols-3">
        <StatCard
          label="Waiting on you"
          value={pendingTaskCount}
          hint={pendingTaskCount === 1 ? "1 task needs a decision" : "tasks need a decision"}
          icon={ClipboardList}
          tone={pendingTaskCount > 0 ? "accent" : "neutral"}
        />
        <StatCard
          label="You submitted"
          value={submittedInstances.length}
          hint="requests raised by you"
          icon={Send}
        />
        <StatCard
          label="Recent activity"
          value={recentActivity.length}
          hint="events on your account"
          icon={History}
        />
      </div>

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
          <p className="rounded-xl border border-gray-200 bg-white px-4 py-6 text-center text-sm text-gray-600">
            Nothing is waiting on you right now.
          </p>
        ) : (
          <div className="overflow-hidden rounded-xl border border-gray-200 bg-white">
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
                        className="text-primary-700 hover:underline"
                      >
                        {task.nodeLabel ?? task.nodeType}
                      </Link>
                    </th>
                    <td className="px-4 py-3 text-gray-700">{task.workflowName}</td>
                    <td className="px-4 py-3">
                      <Badge tone={TASK_STATUS_TONES[task.status] ?? "neutral"}>
                        {TASK_STATUS_LABELS[task.status]}
                      </Badge>
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
          <p className="rounded-xl border border-gray-200 bg-white px-4 py-6 text-center text-sm text-gray-600">
            You have not submitted any requests yet.
          </p>
        ) : (
          <div className="overflow-hidden rounded-xl border border-gray-200 bg-white">
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
                      <Badge tone={INSTANCE_STATUS_TONES[instance.status] ?? "neutral"}>
                        {INSTANCE_STATUS_LABELS[instance.status]}
                      </Badge>
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
          <p className="rounded-xl border border-gray-200 bg-white px-4 py-6 text-center text-sm text-gray-600">
            No recorded activity yet.
          </p>
        ) : (
          <ol className="divide-y divide-gray-200 rounded-xl border border-gray-200 bg-white">
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
