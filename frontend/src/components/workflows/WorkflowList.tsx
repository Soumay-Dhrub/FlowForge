"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertCircle, Copy, Plus, Search, Workflow as WorkflowIcon } from "lucide-react";
import { extractErrorMessage, isForbiddenError } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import {
  WORKFLOW_STATUS_LABELS,
  cloneWorkflow,
  fetchWorkflows,
  workflowKeys,
} from "@/lib/workflowsApi";
import Badge, { type BadgeTone } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import Card from "@/components/ui/Card";
import EmptyState from "@/components/ui/EmptyState";
import NotAuthorized from "@/components/ui/NotAuthorized";
import PageHeader from "@/components/ui/PageHeader";
import { SkeletonTableRows } from "@/components/ui/Skeleton";
import CreateWorkflowModal from "@/components/workflows/CreateWorkflowModal";
import type { Workflow } from "@/types";

const SEARCH_DEBOUNCE_MS = 250;

const COLUMNS = ["Name", "Status", "Created by", "Last updated", "Actions"] as const;

const STATUS_TONES: Record<Workflow["status"], BadgeTone> = {
  DRAFT: "neutral",
  ACTIVE: "success",
  ARCHIVED: "warning",
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
  const trimmedSearch = debouncedSearch.trim();
  const isFiltered = trimmedSearch.length > 0;

  // Authoring is ADMIN/MANAGER only. An EMPLOYEE who reaches this URL is told so plainly rather
  // than shown a table that failed for reasons it does not explain.
  if (workflows.isError && isForbiddenError(workflows.error)) {
    return (
      <NotAuthorized message="Only administrators and managers can view workflow definitions." />
    );
  }

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader
        title="Workflows"
        description="Every workflow definition, newest first. Open one to see its version history."
        actions={
          <Button variant="primary" icon={Plus} onClick={() => setCreateOpen(true)}>
            New workflow
          </Button>
        }
      />

      {notice ? (
        <p
          role="status"
          className="mb-4 animate-scale-in rounded-xl border border-success-200 bg-success-50 px-3.5 py-2.5 text-sm text-success-800"
        >
          {notice}
        </p>
      ) : null}
      {cloneError ? (
        <p
          role="alert"
          className="mb-4 flex items-start gap-2 animate-scale-in rounded-xl border border-danger-200 bg-danger-50 px-3.5 py-2.5 text-sm text-danger-800"
        >
          <AlertCircle aria-hidden className="mt-0.5 h-4 w-4 shrink-0 text-danger-600" />
          {cloneError}
        </p>
      ) : null}

      <Card padded={false} className="overflow-hidden">
        {/*
          Search lives in the table's own header rather than floating above the card, so it is visibly a
          control over these rows and not a global one.
        */}
        <div className="flex flex-wrap items-end justify-between gap-3 border-b border-gray-200 bg-gray-50/70 px-4 py-3">
          <div className="w-full max-w-xs space-y-1">
            <label htmlFor="workflow-search" className="block text-xs font-medium text-gray-600">
              Search by name
            </label>
            <div className="group relative">
              <Search
                aria-hidden
                className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400 transition-colors group-focus-within:text-primary-600"
              />
              <input
                id="workflow-search"
                type="search"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder="e.g. expense"
                className="h-9 w-full rounded-lg border border-gray-300 bg-white pl-9 pr-3 text-sm text-gray-900 shadow-xs transition-colors placeholder:text-gray-400 hover:border-gray-400"
              />
            </div>
          </div>
          {workflows.isSuccess && rows.length > 0 ? (
            <p className="text-xs text-gray-500">
              {rows.length} {rows.length === 1 ? "definition" : "definitions"}
              {isFiltered ? " matching" : ""}
            </p>
          ) : null}
        </div>

        <div className="overflow-x-auto">
          <table className="min-w-full divide-y divide-gray-200 text-sm">
            <caption className="sr-only">Workflows, newest first</caption>
            <thead className="bg-gray-50 text-left text-xs font-medium uppercase tracking-wide text-gray-500">
              <tr>
                {COLUMNS.map((column) => (
                  <th key={column} scope="col" className="whitespace-nowrap px-4 py-3">
                    {column}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {workflows.isPending ? (
                <SkeletonTableRows rows={4} columns={COLUMNS.length} label="Loading workflows" />
              ) : null}

              {workflows.isError ? (
                <tr>
                  <td colSpan={COLUMNS.length} className="px-4 py-10 text-center">
                    <p role="alert" className="text-sm text-danger-700">
                      {extractErrorMessage(workflows.error, "Could not load workflows.")}
                    </p>
                    <Button className="mt-3" onClick={() => workflows.refetch()}>
                      Try again
                    </Button>
                  </td>
                </tr>
              ) : null}

              {workflows.isSuccess && rows.length === 0 ? (
                <tr>
                  <td colSpan={COLUMNS.length}>
                    {/*
                      A search that matches nothing is not the same as having no workflows: the first is a
                      dead end to back out of, the second is an invitation to create one.
                    */}
                    <EmptyState
                      filtered={isFiltered}
                      icon={isFiltered ? undefined : WorkflowIcon}
                      title={
                        isFiltered
                          ? `No workflows match “${trimmedSearch}”.`
                          : "No workflows yet. Create one to get started."
                      }
                      description={
                        isFiltered
                          ? "Names are matched loosely, so a shorter term will usually find more."
                          : "A workflow describes the path a request takes and who has to approve it."
                      }
                      action={
                        isFiltered ? (
                          <Button onClick={() => setSearch("")}>Clear search</Button>
                        ) : (
                          <Button variant="primary" icon={Plus} onClick={() => setCreateOpen(true)}>
                            Create the first workflow
                          </Button>
                        )
                      }
                    />
                  </td>
                </tr>
              ) : null}

              {rows.map((workflow) => {
                const cloning = clone.isPending && clone.variables?.id === workflow.id;
                return (
                  <tr key={workflow.id} className="transition-colors hover:bg-gray-50/80">
                    <th scope="row" className="px-4 py-3 text-left font-medium">
                      <Link
                        href={`/workflows/${workflow.id}`}
                        className="rounded font-medium text-primary-700 hover:text-primary-800 hover:underline"
                      >
                        {workflow.name}
                      </Link>
                      {workflow.description ? (
                        <span className="mt-0.5 block max-w-md truncate text-xs font-normal text-gray-500">
                          {workflow.description}
                        </span>
                      ) : null}
                    </th>
                    <td className="px-4 py-3">
                      <Badge tone={STATUS_TONES[workflow.status]}>
                        {WORKFLOW_STATUS_LABELS[workflow.status]}
                      </Badge>
                    </td>
                    <td className="px-4 py-3 text-gray-600">{workflow.createdByName ?? "—"}</td>
                    <td className="whitespace-nowrap px-4 py-3 text-gray-600">
                      <time dateTime={workflow.updatedAt}>{formatDateTime(workflow.updatedAt)}</time>
                    </td>
                    <td className="px-4 py-3">
                      {/*
                        The workflow name is in the label but not on screen: "Clone" repeated down the
                        column is unambiguous visually, because the row is right there, and ambiguous to a
                        screen reader, because it is not.
                      */}
                      <Button
                        size="sm"
                        icon={Copy}
                        loading={cloning}
                        loadingLabel="Cloning…"
                        onClick={() => clone.mutate(workflow)}
                      >
                        Clone
                        <span className="sr-only"> {workflow.name}</span>
                      </Button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </Card>

      <CreateWorkflowModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={(workflow) => setNotice(`Created “${workflow.name}”.`)}
      />
    </div>
  );
}

export default WorkflowList;
