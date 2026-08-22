"use client";

/**
 * The audit log viewer (Requirements 19.3, 19.4). ADMIN only.
 *
 * <h2>Paging is server-side</h2>
 * The trail is the one table that only grows, and the endpoint caps a page at 500 entries however
 * large a size is asked for. So the page number is a query parameter, not a slice of an array the
 * client holds: filtering or paging in the browser would search whatever subset happened to be
 * fetched, which in an audit tool is worse than not searching at all.
 *
 * <h2>A missing actor is data, not a gap</h2>
 * `actorId` is nullable because the FK is `ON DELETE SET NULL` — the entry outlives the account. It is
 * rendered as "System / deleted user" rather than left blank, so the row reads as a deliberate fact
 * instead of a rendering failure.
 */
import { useMemo, useState } from "react";
import { keepPreviousData, useMutation, useQuery } from "@tanstack/react-query";
import { AlertCircle, Download, ScrollText, SlidersHorizontal } from "lucide-react";
import { extractErrorMessage, isForbiddenError } from "@/lib/api";
import {
  AUDIT_ACTIONS,
  AUDIT_ENTITY_TYPES,
  AUDIT_PAGE_SIZE,
  auditLogKeys,
  exportAuditLogsCsv,
  searchAuditLogs,
  type AuditLogFilters,
} from "@/lib/auditApi";
import { formatAuditAction, formatDateTime } from "@/lib/format";
import { fetchUsers, userKeys } from "@/lib/usersApi";
import { useAuth } from "@/context/AuthContext";
import Button from "@/components/ui/Button";
import Card from "@/components/ui/Card";
import EmptyState from "@/components/ui/EmptyState";
import NotAuthorized from "@/components/ui/NotAuthorized";
import PageHeader from "@/components/ui/PageHeader";
import SelectField from "@/components/ui/SelectField";
import { SkeletonTableRows } from "@/components/ui/Skeleton";
import TextField from "@/components/ui/TextField";

const ADMIN_ONLY_MESSAGE = "Only administrators can read the audit trail.";

/** What a row shows where the actor is gone, or where there never was one. */
const ABSENT_ACTOR = "System / deleted user";

const NO_FILTERS: AuditLogFilters = {
  userId: "",
  entityType: "",
  action: "",
  dateFrom: "",
  dateTo: "",
};

const COLUMNS = ["When", "Actor", "Action", "Entity type", "Entity", "Changes"] as const;

/** The before/after diff, folded away: most rows are read for what happened, not what changed. */
function StateDetails({
  label,
  state,
}: {
  label: string;
  state: Record<string, unknown> | null;
}) {
  if (!state) {
    return <span className="text-xs text-gray-400">No {label.toLowerCase()} state</span>;
  }
  return (
    <details className="text-xs">
      <summary className="cursor-pointer rounded font-medium text-primary-700 hover:text-primary-800">
        {label}
      </summary>
      <pre className="mt-1 max-w-xs overflow-x-auto whitespace-pre-wrap break-all rounded-lg border border-gray-200 bg-gray-50 p-2 font-mono text-[11px] leading-relaxed text-gray-700">
        {JSON.stringify(state, null, 2)}
      </pre>
    </details>
  );
}

export function AuditLogTable() {
  const { user: caller, status } = useAuth();
  const [filters, setFilters] = useState<AuditLogFilters>(NO_FILTERS);
  const [page, setPage] = useState(0);
  const [exportError, setExportError] = useState<string | null>(null);

  const isAdmin = caller?.roleName === "ADMIN";

  /** Any filter change returns to the first page: page 4 of a new result set means nothing. */
  function updateFilter<K extends keyof AuditLogFilters>(key: K, value: AuditLogFilters[K]) {
    setFilters((current) => ({ ...current, [key]: value }));
    setPage(0);
  }

  const entries = useQuery({
    queryKey: auditLogKeys.search(filters, page),
    queryFn: () => searchAuditLogs(filters, page),
    enabled: isAdmin,
    // Keep the previous page visible while the next one loads, so paging does not blank the table.
    placeholderData: keepPreviousData,
  });

  const users = useQuery({
    queryKey: userKeys.list,
    queryFn: fetchUsers,
    enabled: isAdmin,
  });

  const csvExport = useMutation({
    mutationFn: () => exportAuditLogsCsv(filters),
    onSuccess: () => setExportError(null),
    onError: (error) =>
      setExportError(extractErrorMessage(error, "Could not export the audit trail.")),
  });

  /** Actor ids are what the trail stores; names make the table readable when they are available. */
  const actorNames = useMemo(() => {
    const names = new Map<string, string>();
    (users.data ?? []).forEach((row) => names.set(row.id, row.name));
    return names;
  }, [users.data]);

  if (status === "authenticated" && !isAdmin) {
    return <NotAuthorized message={ADMIN_ONLY_MESSAGE} />;
  }
  if (entries.isError && isForbiddenError(entries.error)) {
    return <NotAuthorized message={ADMIN_ONLY_MESSAGE} />;
  }

  const data = entries.data;
  const rows = data?.entries ?? [];
  const totalCount = data?.totalCount ?? 0;
  const firstOnPage = totalCount === 0 ? 0 : page * AUDIT_PAGE_SIZE + 1;
  const lastOnPage = page * AUDIT_PAGE_SIZE + rows.length;
  const hasNextPage = lastOnPage < totalCount;
  const activeFilterCount = Object.values(filters).filter(Boolean).length;
  const isFiltered = activeFilterCount > 0;

  function clearFilters() {
    setFilters(NO_FILTERS);
    setPage(0);
  }

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader
        title="Audit logs"
        description="Every recorded action, newest first. Entries cannot be edited or removed."
        actions={
          <Button
            icon={Download}
            loading={csvExport.isPending}
            loadingLabel="Exporting…"
            onClick={() => csvExport.mutate()}
          >
            Export CSV
          </Button>
        }
      />

      {/*
        The filters are inside a card of their own. Loose above a table they read as part of the table's
        header, and it stops being obvious that a narrow result is the filters' doing rather than the
        data's.
      */}
      <Card className="mb-4">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h2 className="flex items-center gap-2 text-sm font-semibold text-gray-900">
            <SlidersHorizontal aria-hidden className="h-4 w-4 text-gray-400" />
            Filters
            {isFiltered ? (
              <span className="rounded-full bg-primary-50 px-2 py-0.5 text-xs font-medium text-primary-700">
                {activeFilterCount} active
              </span>
            ) : null}
          </h2>
          {isFiltered ? (
            <Button size="sm" variant="ghost" onClick={clearFilters}>
              Clear filters
            </Button>
          ) : null}
        </div>

        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
          <SelectField
            id="audit-filter-user"
            label="User"
            value={filters.userId}
            disabled={users.isPending || users.isError}
            onChange={(event) => updateFilter("userId", event.target.value)}
          >
            <option value="">{users.isPending ? "Loading users…" : "Any user"}</option>
            {(users.data ?? []).map((row) => (
              <option key={row.id} value={row.id}>
                {row.name}
              </option>
            ))}
          </SelectField>

          <TextField
            id="audit-filter-entity-type"
            label="Entity type"
            list="audit-entity-types"
            placeholder="e.g. Task"
            value={filters.entityType}
            onChange={(event) => updateFilter("entityType", event.target.value)}
          />
          <datalist id="audit-entity-types">
            {AUDIT_ENTITY_TYPES.map((value) => (
              <option key={value} value={value} />
            ))}
          </datalist>

          <TextField
            id="audit-filter-action"
            label="Action"
            list="audit-actions"
            placeholder="e.g. APPROVE_TASK"
            value={filters.action}
            onChange={(event) => updateFilter("action", event.target.value)}
          />
          <datalist id="audit-actions">
            {AUDIT_ACTIONS.map((value) => (
              <option key={value} value={value} />
            ))}
          </datalist>

          <TextField
            id="audit-filter-from"
            label="From"
            type="date"
            value={filters.dateFrom}
            max={filters.dateTo || undefined}
            onChange={(event) => updateFilter("dateFrom", event.target.value)}
          />
          <TextField
            id="audit-filter-to"
            label="To"
            type="date"
            value={filters.dateTo}
            min={filters.dateFrom || undefined}
            onChange={(event) => updateFilter("dateTo", event.target.value)}
          />
        </div>
      </Card>

      {users.isError ? (
        <p
          role="alert"
          className="mb-4 flex items-start gap-2 rounded-xl border border-warning-200 bg-warning-50 px-3.5 py-2.5 text-sm text-warning-800"
        >
          <AlertCircle aria-hidden className="mt-0.5 h-4 w-4 shrink-0 text-warning-600" />
          Could not load the user options, so that filter is unavailable and actors below are shown by
          id.
        </p>
      ) : null}
      {exportError ? (
        <p
          role="alert"
          className="mb-4 flex items-start gap-2 animate-scale-in rounded-xl border border-danger-200 bg-danger-50 px-3.5 py-2.5 text-sm text-danger-800"
        >
          <AlertCircle aria-hidden className="mt-0.5 h-4 w-4 shrink-0 text-danger-600" />
          {exportError}
        </p>
      ) : null}

      <Card padded={false} className="overflow-hidden">
        <div className="overflow-x-auto">
          <table className="min-w-full divide-y divide-gray-200 text-sm">
            <caption className="sr-only">Audit log entries, newest first</caption>
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
              {entries.isPending ? (
                <SkeletonTableRows
                  rows={6}
                  columns={COLUMNS.length}
                  label="Loading audit entries"
                />
              ) : null}

              {entries.isError ? (
                <tr>
                  <td colSpan={COLUMNS.length} className="px-4 py-10 text-center">
                    <p role="alert" className="text-sm text-danger-700">
                      {extractErrorMessage(entries.error, "Could not load the audit trail.")}
                    </p>
                    <Button className="mt-3" onClick={() => entries.refetch()}>
                      Try again
                    </Button>
                  </td>
                </tr>
              ) : null}

              {entries.isSuccess && rows.length === 0 ? (
                <tr>
                  <td colSpan={COLUMNS.length}>
                    {/*
                      An empty trail and an over-narrow filter look identical in a table and mean opposite
                      things, so the filtered case names the filters as the cause and offers the way back.
                    */}
                    <EmptyState
                      filtered={isFiltered}
                      icon={isFiltered ? undefined : ScrollText}
                      title={
                        isFiltered
                          ? "No entries match these filters."
                          : "No audit entries have been recorded yet."
                      }
                      description={
                        isFiltered
                          ? "The trail is complete — these filters simply exclude everything in it. Clear them above to see it."
                          : "Actions are recorded as people use the platform. Nothing has happened yet."
                      }
                    />
                  </td>
                </tr>
              ) : null}

              {rows.map((entry) => (
                <tr key={entry.id} className="transition-colors hover:bg-gray-50/80">
                  <th
                    scope="row"
                    className="whitespace-nowrap px-4 py-3 text-left font-medium text-gray-900"
                  >
                    <time dateTime={entry.createdAt}>{formatDateTime(entry.createdAt)}</time>
                  </th>
                  <td className="px-4 py-3 text-gray-700">
                    {entry.actorId === null ? (
                      <span className="italic text-gray-400">{ABSENT_ACTOR}</span>
                    ) : (
                      (actorNames.get(entry.actorId) ?? entry.actorId)
                    )}
                  </td>
                  <td className="px-4 py-3">
                    {/*
                      Both forms are shown: the readable one to scan, and the raw constant because that is
                      what someone will paste into the Action filter or grep for in a support thread.
                    */}
                    <span className="font-medium text-gray-900">
                      {formatAuditAction(entry.action)}
                    </span>
                    <span className="mt-0.5 block font-mono text-[11px] text-gray-400">
                      {entry.action}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-gray-700">{entry.entityType}</td>
                  <td className="px-4 py-3 font-mono text-xs text-gray-500">{entry.entityId}</td>
                  <td className="px-4 py-3">
                    <div className="space-y-1">
                      <StateDetails label="Before" state={entry.beforeState} />
                      <StateDetails label="After" state={entry.afterState} />
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {/*
          Paging sits inside the card, against the table it controls. Floating below it, the range and the
          buttons looked like page furniture rather than part of the result.
        */}
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-gray-200 bg-gray-50/70 px-4 py-3">
          <p role="status" className="text-sm text-gray-600">
            {totalCount === 0
              ? "No matching entries"
              : `Showing ${firstOnPage}–${lastOnPage} of ${totalCount}`}
          </p>
          <div className="flex items-center gap-2">
            <Button
              size="sm"
              onClick={() => setPage((current) => Math.max(0, current - 1))}
              disabled={page === 0 || entries.isFetching}
            >
              Previous page
            </Button>
            <span className="px-1 text-sm tabular-nums text-gray-600">Page {page + 1}</span>
            <Button
              size="sm"
              onClick={() => setPage((current) => current + 1)}
              disabled={!hasNextPage || entries.isFetching}
            >
              Next page
            </Button>
          </div>
        </div>
      </Card>
    </div>
  );
}

export default AuditLogTable;
