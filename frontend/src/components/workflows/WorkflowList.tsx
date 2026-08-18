"use client";

/**
 * The workflow table (Requirements 8.1, 8.2, 8.3 entry point).
 *
 * Search is served by the backend's `?name=` filter rather than by filtering the rows in hand: the
 * client only holds what the server sent, so a client-side filter would quietly search a subset.
 * Keystrokes are debounced so typing a word is one request, not one per letter.
 */
import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Copy, Loader2, Plus, Search } from "lucide-react";
import { extractErrorMessage, isForbiddenError } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import {
  WORKFLOW_STATUS_LABELS,
  cloneWorkflow,
  fetchWorkflows,
  workflowKeys,
} from "@/lib/workflowsApi";
import NotAuthorized from "@/components/ui/NotAuthorized";
import CreateWorkflowModal from "@/components/workflows/CreateWorkflowModal";
import type { Workflow } from "@/types";

const SEARCH_DEBOUNCE_MS = 250;

const STATUS_STYLES: Record<Workflow["status"], string> = {
  DRAFT: "bg-gray-100 text-gray-700",
  ACTIVE: "bg-green-100 text-success-800",
  ARCHIVED: "bg-amber-100 text-warning-800",
};

export function WorkflowList() {
  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [cloneError, setCloneError] = useState<string | null>(null);
  const queryClient = useQueryClient();

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [search]);

  const workflows = useQuery({
    queryKey: workflowKeys.list(debouncedSearch.trim()),
    queryFn: () => fetchWorkflows(debouncedSearch),
  });

  const clone = useMutation({
    mutationFn: (workflow: Workflow) => cloneWorkflow(workflow.id),
    onSuccess: (created) => {
      setCloneError(null);
      setNotice(`Cloned into “${created.name}”.`);
      queryClient.invalidateQueries({ queryKey: workflowKeys.all });
    },
    onError: (error) => {
      setNotice(null);
      setCloneError(extractErrorMessage(error, "Could not clone the workflow. Try again."));
    },
  });

  const rows = useMemo(() => workflows.data ?? [], [workflows.data]);
  const isFiltered = debouncedSearch.trim().length > 0;

  // Authoring is ADMIN/MANAGER only. An EMPLOYEE who reaches this URL is told so plainly rather
  // than shown a table that failed for reasons it does not explain.
  if (workflows.isError && isForbiddenError(workflows.error)) {
    return (
      <NotAuthorized message="Only administrators and managers can view workflow definitions." />
    );
  }

  return (
    <div className="mx-auto max-w-5xl">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-primary-700">Workflows</h1>
          <p className="mt-1 text-sm text-gray-600">
            Every workflow definition, newest first. Open one to see its version history.
          </p>
        </div>
        <button
          type="button"
          onClick={() => setCreateOpen(true)}
          className="inline-flex items-center gap-2 rounded-md bg-primary-600 px-3 py-2 text-sm font-medium text-white hover:bg-primary-700 focus:ring-offset-2"
        >
          <Plus aria-hidden="true" className="h-4 w-4" />
          New workflow
        </button>
      </div>

      <div className="mt-6 max-w-sm space-y-1">
        <label htmlFor="workflow-search" className="block text-sm font-medium text-gray-700">
          Search by name
        </label>
        <div className="relative">
          <Search
            aria-hidden="true"
            className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400"
          />
          <input
            id="workflow-search"
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="e.g. expense"
            className="w-full rounded-md border border-gray-300 py-2 pl-9 pr-3 text-gray-900 shadow-sm outline-none focus:ring-2 focus:ring-primary-500"
          />
        </div>
      </div>

      {notice ? (
        <p role="status" className="mt-4 rounded-md bg-success-50 px-3 py-2 text-sm text-success-800">
          {notice}
        </p>
      ) : null}
      {cloneError ? (
        <p role="alert" className="mt-4 rounded-md bg-danger-50 px-3 py-2 text-sm text-danger-700">
          {cloneError}
        </p>
      ) : null}

      <div className="mt-4 overflow-hidden rounded-xl border border-gray-200 bg-white">
        <table className="min-w-full divide-y divide-gray-200 text-sm">
          <caption className="sr-only">Workflows, newest first</caption>
          <thead className="bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
            <tr>
              <th scope="col" className="px-4 py-3">
                Name
              </th>
              <th scope="col" className="px-4 py-3">
                Status
              </th>
              <th scope="col" className="px-4 py-3">
                Created by
              </th>
              <th scope="col" className="px-4 py-3">
                Last updated
              </th>
              <th scope="col" className="px-4 py-3">
                Actions
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200">
            {workflows.isPending ? (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-gray-600">
                  <span role="status" className="inline-flex items-center gap-2">
                    <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
                    Loading workflows…
                  </span>
                </td>
              </tr>
            ) : null}

            {workflows.isError ? (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center">
                  <p role="alert" className="text-sm text-danger-700">
                    {extractErrorMessage(workflows.error, "Could not load workflows.")}
                  </p>
                  <button
                    type="button"
                    onClick={() => workflows.refetch()}
                    className="mt-3 rounded-md border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50"
                  >
                    Try again
                  </button>
                </td>
              </tr>
            ) : null}

            {workflows.isSuccess && rows.length === 0 ? (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-sm text-gray-600">
                  {isFiltered
                    ? `No workflows match “${debouncedSearch.trim()}”.`
                    : "No workflows yet. Create one to get started."}
                </td>
              </tr>
            ) : null}

            {rows.map((workflow) => (
              <tr key={workflow.id} className="hover:bg-gray-50">
                <th scope="row" className="px-4 py-3 text-left font-medium text-gray-900">
                  <Link
                    href={`/workflows/${workflow.id}`}
                    className="text-primary-700 hover:underline"
                  >
                    {workflow.name}
                  </Link>
                  {workflow.description ? (
                    <span className="block text-xs font-normal text-gray-500">
                      {workflow.description}
                    </span>
                  ) : null}
                </th>
                <td className="px-4 py-3">
                  <span
                    className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_STYLES[workflow.status]}`}
                  >
                    {WORKFLOW_STATUS_LABELS[workflow.status]}
                  </span>
                </td>
                <td className="px-4 py-3 text-gray-700">{workflow.createdByName ?? "—"}</td>
                <td className="px-4 py-3 text-gray-700">{formatDateTime(workflow.updatedAt)}</td>
                <td className="px-4 py-3">
                  <button
                    type="button"
                    onClick={() => clone.mutate(workflow)}
                    disabled={clone.isPending}
                    aria-busy={clone.isPending && clone.variables?.id === workflow.id}
                    className="inline-flex items-center gap-1.5 rounded-md border border-gray-300 px-2.5 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-60"
                  >
                    <Copy aria-hidden="true" className="h-3.5 w-3.5" />
                    {clone.isPending && clone.variables?.id === workflow.id
                      ? "Cloning…"
                      : "Clone"}
                    <span className="sr-only"> {workflow.name}</span>
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <CreateWorkflowModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={(workflow) => setNotice(`Created “${workflow.name}”.`)}
      />
    </div>
  );
}

export default WorkflowList;
