"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { FilterX } from "lucide-react";
import { extractErrorMessage } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import Badge, { TASK_STATUS_TONES } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import Card from "@/components/ui/Card";
import EmptyState from "@/components/ui/EmptyState";
import PageHeader from "@/components/ui/PageHeader";
import { SkeletonTableRows } from "@/components/ui/Skeleton";
import {
  TASK_STATUSES,
  TASK_STATUS_LABELS,
  endOfDayInstant,
  listTasks,
  startOfDayInstant,
  taskKeys,
} from "@/lib/tasksApi";
import { fetchWorkflows, workflowKeys } from "@/lib/workflowsApi";
import { useAuth } from "@/context/AuthContext";
import SelectField from "@/components/ui/SelectField";
import TextField from "@/components/ui/TextField";
import type { TaskStatus } from "@/types";

export function TaskList() {
  const { user } = useAuth();
  const [status, setStatus] = useState<TaskStatus | "">("");
  const [workflowId, setWorkflowId] = useState("");
  const [fromDate, setFromDate] = useState("");
  const [toDate, setToDate] = useState("");

  const canFilterByWorkflow = user?.roleName === "ADMIN" || user?.roleName === "MANAGER";

  const filters = useMemo(
    () => ({
      status,
      workflowId,
      createdFrom: startOfDayInstant(fromDate),
      createdTo: endOfDayInstant(toDate),
    }),
    [status, workflowId, fromDate, toDate],
  );

  const tasks = useQuery({
    queryKey: taskKeys.list(filters),
    queryFn: () => listTasks(filters),
  });

  const workflows = useQuery({
    queryKey: workflowKeys.list(""),
    queryFn: () => fetchWorkflows(),
    enabled: canFilterByWorkflow,
  });

  const rows = useMemo(() => tasks.data ?? [], [tasks.data]);
  const isFiltered = Boolean(status || workflowId || fromDate || toDate);

  const clearFilters = () => {
    setStatus("");
    setWorkflowId("");
    setFromDate("");
    setToDate("");
  };

  return (
    <div>
      <PageHeader
        title="Tasks"
        description="Everything assigned to you, newest first. Open a task to see the request and record a decision."
        actions={
          isFiltered ? (
            <Button variant="secondary" size="sm" icon={FilterX} onClick={clearFilters}>
              Clear filters
            </Button>
          ) : undefined
        }
      />

      <Card className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <SelectField
          id="task-filter-status"
          label="Status"
          value={status}
          onChange={(event) => setStatus(event.target.value as TaskStatus | "")}
        >
          <option value="">Any status</option>
          {TASK_STATUSES.map((value) => (
            <option key={value} value={value}>
              {TASK_STATUS_LABELS[value]}
            </option>
          ))}
        </SelectField>

        {canFilterByWorkflow ? (
          <SelectField
            id="task-filter-workflow"
            label="Workflow"
            value={workflowId}
            disabled={workflows.isPending || workflows.isError}
            onChange={(event) => setWorkflowId(event.target.value)}
          >
            <option value="">
              {workflows.isPending ? "Loading workflows…" : "Any workflow"}
            </option>
            {(workflows.data ?? []).map((workflow) => (
              <option key={workflow.id} value={workflow.id}>
                {workflow.name}
              </option>
            ))}
          </SelectField>
        ) : null}

        <TextField
          id="task-filter-from"
          label="Raised from"
          type="date"
          value={fromDate}
          max={toDate || undefined}
          onChange={(event) => setFromDate(event.target.value)}
        />
        <TextField
          id="task-filter-to"
          label="Raised to"
          type="date"
          value={toDate}
          min={fromDate || undefined}
          onChange={(event) => setToDate(event.target.value)}
        />
      </Card>

      {canFilterByWorkflow && workflows.isError ? (
        <p role="alert" className="mt-4 text-sm text-danger-700">
          Could not load the workflow options, so that filter is unavailable. The task list below is
          unaffected.
        </p>
      ) : null}

      <div className="mt-4 overflow-hidden rounded-xl border border-gray-200 bg-white shadow-xs">
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm">
            <caption className="sr-only">Tasks assigned to you, newest first</caption>
            <thead>
              <tr className="border-b border-gray-200 bg-gray-50/80 text-left text-xs font-medium uppercase tracking-wide text-gray-500">
                <th scope="col" className="px-4 py-3">
                  Workflow
                </th>
                <th scope="col" className="px-4 py-3">
                  Step
                </th>
                <th scope="col" className="px-4 py-3">
                  Status
                </th>
                <th scope="col" className="px-4 py-3">
                  Due
                </th>
                <th scope="col" className="px-4 py-3">
                  Raised
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {tasks.isPending ? (
                <SkeletonTableRows rows={5} columns={5} label="Loading tasks" />
              ) : null}

              {tasks.isError ? (
                <tr>
                  <td colSpan={5} className="px-4 py-10 text-center">
                    <p role="alert" className="text-sm text-danger-700">
                      {extractErrorMessage(tasks.error, "Could not load tasks.")}
                    </p>
                    <Button
                      variant="secondary"
                      size="sm"
                      className="mt-3"
                      onClick={() => tasks.refetch()}
                    >
                      Try again
                    </Button>
                  </td>
                </tr>
              ) : null}

              {tasks.isSuccess && rows.length === 0 ? (
                <tr>
                  <td colSpan={5}>
                    <EmptyState
                      filtered={isFiltered}
                      title={isFiltered ? "No tasks match these filters" : "Nothing waiting on you"}
                      description={
                        isFiltered
                          ? "Try widening the date range, or clear the filters to see everything."
                          : "Anything assigned to you will appear here."
                      }
                      action={
                        isFiltered ? (
                          <Button variant="secondary" size="sm" icon={FilterX} onClick={clearFilters}>
                            Clear filters
                          </Button>
                        ) : undefined
                      }
                    />
                  </td>
                </tr>
              ) : null}

              {rows.map((task) => (
                <tr key={task.id} className="transition-colors hover:bg-gray-50/70">
                  <th scope="row" className="px-4 py-3 text-left font-medium">
                    <Link
                      href={`/tasks/${task.id}`}
                      className="rounded text-primary-700 hover:text-primary-800 hover:underline"
                    >
                      {task.workflowName}
                    </Link>
                  </th>
                  <td className="px-4 py-3">
                    <span className="text-gray-800">{task.nodeLabel ?? task.nodeType}</span>
                    {task.nodeLabel ? (
                      <span className="mt-0.5 block text-xs text-gray-400">{task.nodeType}</span>
                    ) : null}
                  </td>
                  <td className="px-4 py-3">
                    <Badge tone={TASK_STATUS_TONES[task.status] ?? "neutral"}>
                      {TASK_STATUS_LABELS[task.status]}
                    </Badge>
                  </td>
                  <td className="whitespace-nowrap px-4 py-3 text-gray-600">
                    {formatDateTime(task.dueAt)}
                  </td>
                  <td className="whitespace-nowrap px-4 py-3 text-gray-600">
                    {formatDateTime(task.createdAt)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

export default TaskList;
