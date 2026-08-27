"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useMutation, useQuery } from "@tanstack/react-query";
import { AlertTriangle, Download, FileJson, Loader2 } from "lucide-react";
import { extractErrorMessage, isForbiddenError } from "@/lib/api";
import { formatDurationSeconds, formatRatioAsPercent } from "@/lib/format";
import { fetchDepartments, referenceDataKeys } from "@/lib/referenceDataApi";
import {
  exportPerformanceCsv,
  exportPerformanceJson,
  fetchWorkflowPerformance,
  reportKeys,
  type PerformanceFilterInput,
} from "@/lib/reportsApi";
import { useAuth } from "@/context/AuthContext";
import Button from "@/components/ui/Button";
import NotAuthorized from "@/components/ui/NotAuthorized";
import PageHeader from "@/components/ui/PageHeader";
import SelectField from "@/components/ui/SelectField";
import TextField from "@/components/ui/TextField";
import type { NodePerformance, WorkflowPerformance } from "@/types";

const REPORTS_ROLE_MESSAGE = "Only administrators and managers can view workflow analytics.";

/** The text a metric with no population behind it shows. Never a zero. */
const NO_DATA_LABEL = "No data";

function MetricCard({
  label,
  value,
  hint,
}: {
  label: string;
  value: string | null;
  hint: string;
}) {
  const hasData = value !== null;
  return (
    <div className="rounded-xl border border-gray-200 bg-white p-4">
      <dt className="text-sm font-medium text-gray-500">{label}</dt>
      <dd
        className={`mt-1 text-2xl font-semibold ${hasData ? "text-gray-900" : "text-gray-400"}`}
      >
        {hasData ? value : NO_DATA_LABEL}
      </dd>
      <p className="mt-1 text-xs text-gray-500">{hint}</p>
    </div>
  );
}

/** A node's own name where the designer gave one, its type otherwise. */
function stageName(node: NodePerformance): string {
  return node.nodeLabel ?? node.nodeType;
}

function BottleneckPanel({ report }: { report: WorkflowPerformance }) {
  if (!report.bottleneckNode) {
    return (
      <p className="mt-6 rounded-xl border border-gray-200 bg-white px-4 py-3 text-sm text-gray-600">
        No bottleneck stage yet: no stage has reached {report.bottleneckMinimumSamples} decided
        {report.bottleneckMinimumSamples === 1 ? " task" : " tasks"}, and a stage named on fewer
        observations than that is an anecdote rather than a constraint.
      </p>
    );
  }

  const node = report.bottleneckNode;
  return (
    <div className="mt-6 rounded-lg border border-amber-300 bg-warning-50 p-4">
      <div className="flex items-start gap-2">
        <AlertTriangle aria-hidden="true" className="mt-0.5 h-5 w-5 text-amber-600" />
        <div>
          <h2 className="text-sm font-semibold uppercase tracking-wide text-amber-900">
            Bottleneck stage
          </h2>
          <p className="mt-1 text-lg font-semibold text-amber-900">{stageName(node)}</p>
          <p className="mt-1 text-sm text-warning-800">
            {formatDurationSeconds(node.averageDwellSeconds)} average dwell over{" "}
            {node.decidedTaskCount} decided {node.decidedTaskCount === 1 ? "task" : "tasks"} (
            {node.nodeType}).
          </p>
        </div>
      </div>
    </div>
  );
}

export function WorkflowPerformanceReport({ workflowId }: { workflowId: string }) {
  const { user, status } = useAuth();
  const [departmentId, setDepartmentId] = useState("");
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [minSamples, setMinSamples] = useState("");
  const [exportError, setExportError] = useState<string | null>(null);

  const canView = user?.roleName === "ADMIN" || user?.roleName === "MANAGER";

  const filters = useMemo<PerformanceFilterInput>(
    () => ({ departmentId, dateFrom, dateTo, minSamples }),
    [departmentId, dateFrom, dateTo, minSamples],
  );

  const report = useQuery({
    queryKey: reportKeys.performance(workflowId, filters),
    queryFn: () => fetchWorkflowPerformance(workflowId, filters),
    // No point asking for a report the caller is not allowed to have.
    enabled: canView,
  });

  const departments = useQuery({
    queryKey: referenceDataKeys.departments,
    queryFn: fetchDepartments,
    enabled: canView,
  });

  const csvExport = useMutation({
    mutationFn: () => exportPerformanceCsv(workflowId, filters),
    onSuccess: () => setExportError(null),
    onError: (error) =>
      setExportError(extractErrorMessage(error, "Could not export this report as CSV.")),
  });

  if (status === "authenticated" && !canView) {
    return <NotAuthorized message={REPORTS_ROLE_MESSAGE} />;
  }
  if (report.isError && isForbiddenError(report.error)) {
    return <NotAuthorized message={REPORTS_ROLE_MESSAGE} />;
  }

  const data = report.data;

  return (
    <div className="mx-auto max-w-5xl">
      <PageHeader
        breadcrumb={
          <Link
            href="/reports"
            className="rounded text-primary-700 hover:text-primary-800 hover:underline"
          >
            ← All reports
          </Link>
        }
        title={data ? data.workflowName : "Workflow performance"}
        description="Approval time, rejection rate, volume and the stage that holds work longest."
        actions={
          <>
            <Button
              icon={Download}
              loading={csvExport.isPending}
              loadingLabel="Exporting…"
              onClick={() => csvExport.mutate()}
            >
              Export CSV
            </Button>
            {/*
              Disabled until the figures are in hand: exporting what has not loaded would write an empty
              file, which looks like a report saying zero rather than one that never arrived.
            */}
            <Button
              icon={FileJson}
              disabled={!data}
              onClick={() => {
                if (data) {
                  exportPerformanceJson(data);
                }
              }}
            >
              Export JSON
            </Button>
          </>
        }
      />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <SelectField
          id="report-filter-department"
          label="Department"
          hint="Department of whoever submitted the request."
          value={departmentId}
          disabled={departments.isPending || departments.isError}
          onChange={(event) => setDepartmentId(event.target.value)}
        >
          <option value="">
            {departments.isPending ? "Loading departments…" : "Every department"}
          </option>
          {(departments.data ?? []).map((department) => (
            <option key={department.id} value={department.id}>
              {department.name}
            </option>
          ))}
        </SelectField>

        <TextField
          id="report-filter-from"
          label="Submitted from"
          type="date"
          value={dateFrom}
          max={dateTo || undefined}
          onChange={(event) => setDateFrom(event.target.value)}
        />
        <TextField
          id="report-filter-to"
          label="Submitted to"
          type="date"
          value={dateTo}
          min={dateFrom || undefined}
          onChange={(event) => setDateTo(event.target.value)}
        />
        <TextField
          id="report-filter-min-samples"
          label="Bottleneck minimum samples"
          type="number"
          min={1}
          value={minSamples}
          hint="Decided tasks a stage needs before it can be named."
          onChange={(event) => setMinSamples(event.target.value)}
        />
      </div>

      {departments.isError ? (
        <p role="alert" className="mt-4 text-sm text-danger-700">
          Could not load the department options, so that filter is unavailable. The figures below are
          unfiltered by department.
        </p>
      ) : null}
      {exportError ? (
        <p role="alert" className="mt-4 rounded-md bg-danger-50 px-3 py-2 text-sm text-danger-700">
          {exportError}
        </p>
      ) : null}

      {report.isPending ? (
        <p role="status" className="mt-6 inline-flex items-center gap-2 text-sm text-gray-600">
          <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
          Loading metrics…
        </p>
      ) : null}

      {report.isError ? (
        <div className="mt-6">
          <p role="alert" className="rounded-md bg-danger-50 px-3 py-2 text-sm text-danger-700">
            {extractErrorMessage(report.error, "Could not load this report.")}
          </p>
          <button
            type="button"
            onClick={() => report.refetch()}
            className="mt-3 rounded-md border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50"
          >
            Try again
          </button>
        </div>
      ) : null}

      {data ? (
        <>
          <dl className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            <MetricCard
              label="Average approval time"
              value={
                data.averageApprovalTimeSeconds === null
                  ? null
                  : formatDurationSeconds(data.averageApprovalTimeSeconds)
              }
              hint={
                data.decidedInstanceCount === 0
                  ? "No request has been decided in this window."
                  : `Submission to decision, over ${data.decidedInstanceCount} decided ${
                      data.decidedInstanceCount === 1 ? "request" : "requests"
                    }.`
              }
            />
            <MetricCard
              label="Rejection rate"
              value={data.rejectionRate === null ? null : formatRatioAsPercent(data.rejectionRate)}
              hint={
                data.decidedInstanceCount === 0
                  ? "Nothing has been decided, so there is no rate to report."
                  : `${data.rejectedInstanceCount} rejected of ${data.decidedInstanceCount} decided.`
              }
            />
            <MetricCard
              label="Total volume"
              value={String(data.totalInstanceVolume)}
              hint={`${data.runningInstanceCount} running · ${data.completedInstanceCount} completed · ${data.rejectedInstanceCount} rejected · ${data.cancelledInstanceCount} cancelled · ${data.erroredInstanceCount} errored.`}
            />
          </dl>

          <BottleneckPanel report={data} />

          <div className="mt-6 overflow-hidden rounded-xl border border-gray-200 bg-white">
            <table className="min-w-full divide-y divide-gray-200 text-sm">
              <caption className="sr-only">Average dwell time per stage, slowest first</caption>
              <thead className="bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
                <tr>
                  <th scope="col" className="px-4 py-3">
                    Stage
                  </th>
                  <th scope="col" className="px-4 py-3">
                    Type
                  </th>
                  <th scope="col" className="px-4 py-3">
                    Decided tasks
                  </th>
                  <th scope="col" className="px-4 py-3">
                    Average dwell
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200">
                {data.nodes.length === 0 ? (
                  <tr>
                    <td colSpan={4} className="px-4 py-8 text-center text-sm text-gray-600">
                      No stage has a decided task in this window, so there is no dwell time to
                      measure.
                    </td>
                  </tr>
                ) : null}
                {data.nodes.map((node) => (
                  <tr key={node.nodeId} className={node.bottleneck ? "bg-warning-50" : undefined}>
                    <th scope="row" className="px-4 py-3 text-left font-medium text-gray-900">
                      {stageName(node)}
                      {node.bottleneck ? (
                        <span className="ml-2 inline-block rounded-full bg-amber-200 px-2 py-0.5 text-xs font-medium text-amber-900">
                          Bottleneck
                        </span>
                      ) : null}
                    </th>
                    <td className="px-4 py-3 text-gray-700">{node.nodeType}</td>
                    <td className="px-4 py-3 text-gray-700">{node.decidedTaskCount}</td>
                    <td className="px-4 py-3 text-gray-700">
                      {formatDurationSeconds(node.averageDwellSeconds)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      ) : null}
    </div>
  );
}

export default WorkflowPerformanceReport;
