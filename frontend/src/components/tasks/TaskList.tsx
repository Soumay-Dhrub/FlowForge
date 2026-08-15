"use client";

/**
 * The task queue (Requirements 12.1, 12.2, 12.3).
 *
 * Every filter is applied by the backend, as on the workflow table: the client only holds the rows the
 * server sent, so filtering in the browser would quietly search a subset. The date controls are plain
 * dates but the endpoint takes instants, so each bound is widened to cover the whole day in the
 * reader's own zone — picking "2 Jan" for both ends must include everything raised on the 2nd.
 *
 * The workflow filter is only offered to ADMIN and MANAGER, because its options come from
 * `GET /api/workflows`, which answers an EMPLOYEE with 403. Rendering the control and firing a request
 * that is guaranteed to fail would be a dead end, not a feature.
 */
import { useMemo, useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { extractErrorMessage } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
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

const STATUS_STYLES: Record<TaskStatus, string> = {
  PENDING: "bg-amber-100 text-amber-800",
  ESCALATED: "bg-red-100 text-red-800",
  DELEGATED: "bg-blue-100 text-blue-800",
  COMPLETED: "bg-green-100 text-green-800",
  CANCELLED: "bg-gray-100 text-gray-700",
};

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

  return (
    <div className="mx-auto max-w-5xl">
      <div>
        <h1 className="text-2xl font-bold text-primary-700">Tasks</h1>
        <p className="mt-1 text-sm text-gray-600">
          Everything assigned to you, newest first. Open a task to see the request and record a
          decision.
        </p>
      </div>

      <div className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
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
      </div>

      {canFilterByWorkflow && workflows.isError ? (
        <p role="alert" className="mt-4 text-sm text-red-700">
          Could not load the workflow options, so that filter is unavailable. The task list below is
          unaffected.
        </p>
      ) : null}

      <div className="mt-4 overflow-hidden rounded-lg border border-gray-200 bg-white">
        <table className="min-w-full divide-y divide-gray-200 text-sm">
          <caption className="sr-only">Tasks assigned to you, newest first</caption>
          <thead className="bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
            <tr>
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
          <tbody className="divide-y divide-gray-200">
            {tasks.isPending ? (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-gray-600">
                  <span role="status" className="inline-flex items-center gap-2">
                    <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
                    Loading tasks…
                  </span>
                </td>
              </tr>
            ) : null}

            {tasks.isError ? (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center">
                  <p role="alert" className="text-sm text-red-700">
                    {extractErrorMessage(tasks.error, "Could not load tasks.")}
                  </p>
                  <button
                    type="button"
                    onClick={() => tasks.refetch()}
                    className="mt-3 rounded-md border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-primary-500"
                  >
                    Try again
                  </button>
                </td>
              </tr>
            ) : null}

            {tasks.isSuccess && rows.length === 0 ? (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-sm text-gray-600">
                  {isFiltered
                    ? "No tasks match these filters."
                    : "You have no tasks. Anything assigned to you will appear here."}
                </td>
              </tr>
            ) : null}

            {rows.map((task) => (
              <tr key={task.id} className="hover:bg-gray-50">
                <th scope="row" className="px-4 py-3 text-left font-medium text-gray-900">
                  <Link
                    href={`/tasks/${task.id}`}
                    className="text-primary-700 hover:underline focus:outline-none focus:ring-2 focus:ring-primary-500"
                  >
                    {task.workflowName}
                  </Link>
                </th>
                <td className="px-4 py-3 text-gray-700">
                  {task.nodeLabel ?? task.nodeType}
                  {task.nodeLabel ? (
                    <span className="block text-xs text-gray-500">{task.nodeType}</span>
                  ) : null}
                </td>
                <td className="px-4 py-3">
                  <span
                    className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_STYLES[task.status]}`}
                  >
                    {TASK_STATUS_LABELS[task.status]}
                  </span>
                </td>
                <td className="px-4 py-3 text-gray-700">{formatDateTime(task.dueAt)}</td>
                <td className="px-4 py-3 text-gray-700">{formatDateTime(task.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export default TaskList;
