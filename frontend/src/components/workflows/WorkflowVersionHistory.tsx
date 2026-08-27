"use client";

import { useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, Copy, Loader2, Pencil } from "lucide-react";
import { extractErrorMessage, isForbiddenError, isStatusError } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import {
  WORKFLOW_STATUS_LABELS,
  WorkflowPublishError,
  cloneWorkflow,
  fetchWorkflow,
  publishVersion,
  versionsNewestFirst,
  workflowKeys,
} from "@/lib/workflowsApi";
import { useAuth } from "@/context/AuthContext";
import Badge, { type BadgeTone } from "@/components/ui/Badge";
import NotAuthorized from "@/components/ui/NotAuthorized";
import PageHeader from "@/components/ui/PageHeader";
import type { Workflow, WorkflowVersion } from "@/types";

/** Same reading as the workflow table, so a definition does not change colour between screens. */
const STATUS_TONES: Record<Workflow["status"], BadgeTone> = {
  DRAFT: "neutral",
  ACTIVE: "success",
  ARCHIVED: "warning",
};

export function WorkflowVersionHistory({ workflowId }: { workflowId: string }) {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const [expandedVersionId, setExpandedVersionId] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [publishFailure, setPublishFailure] = useState<WorkflowPublishError | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const workflow = useQuery({
    queryKey: workflowKeys.detail(workflowId),
    queryFn: () => fetchWorkflow(workflowId),
  });

  const publish = useMutation({
    mutationFn: (version: WorkflowVersion) => publishVersion(workflowId, version.id),
    onSuccess: (published) => {
      setPublishFailure(null);
      setActionError(null);
      setNotice(`Published version ${published.versionNumber}.`);
      queryClient.invalidateQueries({ queryKey: workflowKeys.all });
    },
    onError: (error) => {
      setNotice(null);
      if (error instanceof WorkflowPublishError) {
        setPublishFailure(error);
        setActionError(null);
        return;
      }
      setPublishFailure(null);
      setActionError(extractErrorMessage(error, "Could not publish this version."));
    },
  });

  const clone = useMutation({
    mutationFn: (version: WorkflowVersion) =>
      cloneWorkflow(workflowId, { sourceVersionId: version.id }),
    onSuccess: (created, version) => {
      setActionError(null);
      setPublishFailure(null);
      setNotice(`Copied version ${version.versionNumber} into a new workflow, “${created.name}”.`);
      queryClient.invalidateQueries({ queryKey: workflowKeys.all });
    },
    onError: (error) => {
      setNotice(null);
      setActionError(extractErrorMessage(error, "Could not clone this version."));
    },
  });

  if (workflow.isError && isForbiddenError(workflow.error)) {
    return (
      <NotAuthorized message="Only administrators and managers can view workflow definitions." />
    );
  }

  if (workflow.isPending) {
    return (
      <p role="status" className="inline-flex items-center gap-2 text-sm text-gray-600">
        <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
        Loading workflow…
      </p>
    );
  }

  if (workflow.isError) {
    const missing = isStatusError(workflow.error, 404);
    return (
      <div className="mx-auto max-w-md text-center">
        <p role="alert" className="text-sm text-danger-700">
          {missing
            ? "That workflow does not exist."
            : extractErrorMessage(workflow.error, "Could not load this workflow.")}
        </p>
        {missing ? (
          <Link href="/workflows" className="mt-3 inline-block text-sm text-primary-700 hover:underline">
            Back to workflows
          </Link>
        ) : (
          <button
            type="button"
            onClick={() => workflow.refetch()}
            className="mt-3 rounded-md border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50"
          >
            Try again
          </button>
        )}
      </div>
    );
  }

  const definition = workflow.data;
  const versions = versionsNewestFirst(definition);
  const canPublish = user?.roleName === "ADMIN";

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader
        breadcrumb={
          <Link
            href="/workflows"
            className="rounded text-primary-700 hover:text-primary-800 hover:underline"
          >
            ← Workflows
          </Link>
        }
        title={definition.name}
        description={definition.description ?? undefined}
        actions={
          /* The builder edits the newest unpublished version; it says so itself when there is none. */
          <Link
            href={`/workflows/${definition.id}/edit`}
            className="inline-flex h-9 items-center gap-2 rounded-md bg-primary-600 px-3.5 text-sm font-medium text-white shadow-xs transition-colors hover:bg-primary-700 active:bg-primary-800"
          >
            <Pencil aria-hidden className="h-4 w-4" />
            Open builder
          </Link>
        }
      />

      {/* The facts that do not change per version, kept out of the table that repeats per version. */}
      <dl className="mb-6 flex flex-wrap gap-x-8 gap-y-3 rounded-xl border border-gray-200 bg-white px-4 py-3 text-sm shadow-xs">
        <div>
          <dt className="text-xs uppercase tracking-wide text-gray-500">Status</dt>
          <dd className="mt-1">
            <Badge tone={STATUS_TONES[definition.status]}>
              {WORKFLOW_STATUS_LABELS[definition.status]}
            </Badge>
          </dd>
        </div>
        <div>
          <dt className="text-xs uppercase tracking-wide text-gray-500">Created by</dt>
          <dd className="mt-1 font-medium text-gray-900">{definition.createdByName ?? "—"}</dd>
        </div>
        <div>
          <dt className="text-xs uppercase tracking-wide text-gray-500">Created</dt>
          <dd className="mt-1 font-medium text-gray-900">
            <time dateTime={definition.createdAt}>{formatDateTime(definition.createdAt)}</time>
          </dd>
        </div>
      </dl>

      {notice ? (
        <p role="status" className="mt-4 rounded-md bg-success-50 px-3 py-2 text-sm text-success-800">
          {notice}
        </p>
      ) : null}
      {actionError ? (
        <p role="alert" className="mt-4 rounded-md bg-danger-50 px-3 py-2 text-sm text-danger-700">
          {actionError}
        </p>
      ) : null}
      {publishFailure ? (
        <div role="alert" className="mt-4 rounded-md bg-danger-50 p-3 text-sm text-red-800">
          <p className="font-medium">{publishFailure.message}</p>
          {publishFailure.violations.length > 0 ? (
            <ul className="mt-2 list-disc space-y-1 pl-5">
              {publishFailure.violations.map((violation) => (
                <li key={violation}>{violation}</li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}

      <h2 className="mt-8 text-lg font-semibold text-gray-900">Version history</h2>
      <div className="mt-3 overflow-hidden rounded-xl border border-gray-200 bg-white">
        <table className="min-w-full divide-y divide-gray-200 text-sm">
          <caption className="sr-only">
            Versions of {definition.name}, newest first, with publish timestamps and author
          </caption>
          <thead className="bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
            <tr>
              <th scope="col" className="px-4 py-3">
                Version
              </th>
              <th scope="col" className="px-4 py-3">
                State
              </th>
              <th scope="col" className="px-4 py-3">
                Published
              </th>
              <th scope="col" className="px-4 py-3">
                Published by
              </th>
              <th scope="col" className="px-4 py-3">
                Actions
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200">
            {versions.length === 0 ? (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-sm text-gray-600">
                  This workflow has no versions yet.
                </td>
              </tr>
            ) : null}

            {versions.map((version) => {
              const expanded = expandedVersionId === version.id;
              const publishing = publish.isPending && publish.variables?.id === version.id;
              const cloning = clone.isPending && clone.variables?.id === version.id;
              return [
                <tr key={version.id} className="hover:bg-gray-50">
                  <th scope="row" className="px-4 py-3 text-left font-medium text-gray-900">
                    v{version.versionNumber}
                  </th>
                  <td className="px-4 py-3">
                    <span className="inline-flex flex-wrap gap-1">
                      <span
                        className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                          version.isPublished
                            ? "bg-green-100 text-success-800"
                            : "bg-gray-100 text-gray-700"
                        }`}
                      >
                        {version.isPublished ? "Published" : "Draft"}
                      </span>
                      {version.isCurrent ? (
                        <span className="rounded-full bg-primary-50 px-2 py-0.5 text-xs font-medium text-primary-700">
                          Current
                        </span>
                      ) : null}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-gray-700">{formatDateTime(version.publishedAt)}</td>
                  <td className="px-4 py-3 text-gray-700">{version.publishedByName ?? "—"}</td>
                  <td className="px-4 py-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <button
                        type="button"
                        onClick={() => setExpandedVersionId(expanded ? null : version.id)}
                        aria-expanded={expanded}
                        className="inline-flex items-center gap-1.5 rounded-md border border-gray-300 px-2.5 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50"
                      >
                        {expanded ? (
                          <ChevronDown aria-hidden="true" className="h-3.5 w-3.5" />
                        ) : (
                          <ChevronRight aria-hidden="true" className="h-3.5 w-3.5" />
                        )}
                        View version {version.versionNumber}
                      </button>

                      <button
                        type="button"
                        onClick={() => clone.mutate(version)}
                        disabled={clone.isPending}
                        aria-busy={cloning}
                        className="inline-flex items-center gap-1.5 rounded-md border border-gray-300 px-2.5 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-60"
                      >
                        <Copy aria-hidden="true" className="h-3.5 w-3.5" />
                        {cloning
                          ? "Cloning…"
                          : `Clone version ${version.versionNumber} into a new workflow`}
                      </button>

                      {canPublish && !version.isPublished ? (
                        <button
                          type="button"
                          onClick={() => publish.mutate(version)}
                          disabled={publish.isPending}
                          aria-busy={publishing}
                          className="inline-flex items-center gap-1.5 rounded-md bg-primary-600 px-2.5 py-1.5 text-xs font-medium text-white hover:bg-primary-700 focus:ring-offset-1 disabled:cursor-not-allowed disabled:opacity-60"
                        >
                          {publishing ? "Publishing…" : `Publish version ${version.versionNumber}`}
                        </button>
                      ) : null}
                    </div>
                  </td>
                </tr>,
                expanded ? (
                  <tr key={`${version.id}-graph`} className="bg-gray-50">
                    <td colSpan={5} className="px-4 py-3">
                      <VersionGraph version={version} />
                    </td>
                  </tr>
                ) : null,
              ];
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/** Read-only summary of a version's stored graph. */
function VersionGraph({ version }: { version: WorkflowVersion }) {
  const nodes = version.nodes ?? [];
  const edges = version.edges ?? [];
  const nodeLabel = (nodeId: string) => {
    const node = nodes.find((candidate) => candidate.id === nodeId);
    return node ? node.type : nodeId;
  };

  if (nodes.length === 0 && edges.length === 0) {
    return <p className="text-sm text-gray-600">Version {version.versionNumber} has an empty graph.</p>;
  }

  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <div>
        <h3 className="text-xs font-semibold uppercase tracking-wide text-gray-500">
          Nodes ({nodes.length})
        </h3>
        <ul className="mt-2 space-y-1 text-sm text-gray-800">
          {nodes.map((node) => (
            <li key={node.id}>{node.type}</li>
          ))}
        </ul>
      </div>
      <div>
        <h3 className="text-xs font-semibold uppercase tracking-wide text-gray-500">
          Edges ({edges.length})
        </h3>
        <ul className="mt-2 space-y-1 text-sm text-gray-800">
          {edges.map((edge) => (
            <li key={edge.id}>
              {nodeLabel(edge.sourceNodeId)} → {nodeLabel(edge.targetNodeId)}
              {edge.conditionExpr ? (
                <span className="text-gray-500"> when {edge.conditionExpr}</span>
              ) : null}
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

export default WorkflowVersionHistory;
