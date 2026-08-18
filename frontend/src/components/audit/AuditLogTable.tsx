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
import { Download, Loader2 } from "lucide-react";
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
import NotAuthorized from "@/components/ui/NotAuthorized";
import SelectField from "@/components/ui/SelectField";
import TextField from "@/components/ui/TextField";

const ADMIN_ONLY_MESSAGE = "Only administrators can read the audit trail.";

/** What a row shows where the actor is gone, or where there never was one. */
const ABSENT_ACTOR = "System / deleted user";

/** The before/after diff, folded away: most rows are read for what happened, not what changed. */
function StateDetails({
  label,
  state,
}: {
  label: string;
  state: Record<string, unknown> | null;
}) {
  if (!state) {
    return <span className="text-xs text-gray-500">No {label.toLowerCase()} state</span>;
  }
  return (
    <details className="text-xs">
      <summary className="cursor-pointer text-primary-700 focus:outline-none focus:ring-2 focus:ring-primary-500">
        {label}
      </summary>
      <pre className="mt-1 max-w-xs overflow-x-auto whitespace-pre-wrap break-all rounded bg-gray-50 p-2 text-[11px] text-gray-700">
        {JSON.stringify(state, null, 2)}
      </pre>
    </details>
  );
}

export function AuditLogTable() {
  const { user: caller, status } = useAuth();
  const [filters, setFilters] = useState<AuditLogFilters>({
    userId: "",
    entityType: "",
    action: "",
    dateFrom: "",
    dateTo: "",
  });
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
  const isFiltered = Object.values(filters).some(Boolean);

  return (
    <div className="mx-auto max-w-6xl">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-primary-700">Audit logs</h1>
          <p className="mt-1 text-sm text-gray-600">
            Every recorded action, newest first. Entries cannot be edited or removed.
          </p>
        </div>
        <button
          type="button"
          onClick={() => csvExport.mutate()}
          disabled={csvExport.isPending}
          aria-busy={csvExport.isPending}
          className="inline-flex items-center gap-2 rounded-md border border-gray-300 px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-primary-500 disabled:cursor-not-allowed disabled:opacity-60"
        >
          <Download aria-hidden="true" className="h-4 w-4" />
          {csvExport.isPending ? "Exporting…" : "Export CSV"}
        </button>
      </div>

      <div className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
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

      {users.isError ? (
        <p role="alert" className="mt-4 text-sm text-red-700">
          Could not load the user options, so that filter is unavailable and actors below are shown by
          id.
        </p>
      ) : null}
      {exportError ? (
        <p role="alert" className="mt-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
          {exportError}
        </p>
      ) : null}

      <div className="mt-4 overflow-hidden rounded-lg border border-gray-200 bg-white">
        <table className="min-w-full divide-y divide-gray-200 text-sm">
          <caption className="sr-only">Audit log entries, newest first</caption>
          <thead className="bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
            <tr>
              <th scope="col" className="px-4 py-3">
                When
              </th>
              <th scope="col" className="px-4 py-3">
                Actor
              </th>
              <th scope="col" className="px-4 py-3">
                Action
              </th>
              <th scope="col" className="px-4 py-3">
                Entity type
              </th>
              <th scope="col" className="px-4 py-3">
                Entity
              </th>
              <th scope="col" className="px-4 py-3">
                Changes
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200">
            {entries.isPending ? (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-gray-600">
                  <span role="status" className="inline-flex items-center gap-2">
                    <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
                    Loading audit entries…
                  </span>
                </td>
              </tr>
            ) : null}

            {entries.isError ? (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center">
                  <p role="alert" className="text-sm text-red-700">
                    {extractErrorMessage(entries.error, "Could not load the audit trail.")}
                  </p>
                  <button
                    type="button"
                    onClick={() => entries.refetch()}
                    className="mt-3 rounded-md border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-primary-500"
                  >
                    Try again
                  </button>
                </td>
              </tr>
            ) : null}

            {entries.isSuccess && rows.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-sm text-gray-600">
                  {isFiltered
                    ? "No entries match these filters."
                    : "No audit entries have been recorded yet."}
                </td>
              </tr>
            ) : null}

            {rows.map((entry) => (
              <tr key={entry.id} className="hover:bg-gray-50">
                <th scope="row" className="px-4 py-3 text-left font-medium text-gray-900">
                  <time dateTime={entry.createdAt}>{formatDateTime(entry.createdAt)}</time>
                </th>
                <td className="px-4 py-3 text-gray-700">
                  {entry.actorId === null ? (
                    <span className="text-gray-500">{ABSENT_ACTOR}</span>
                  ) : (
                    (actorNames.get(entry.actorId) ?? entry.actorId)
                  )}
                </td>
                <td className="px-4 py-3 text-gray-700">
                  {formatAuditAction(entry.action)}
                  <span className="block text-xs text-gray-500">{entry.action}</span>
                </td>
                <td className="px-4 py-3 text-gray-700">{entry.entityType}</td>
                <td className="px-4 py-3 font-mono text-xs text-gray-600">{entry.entityId}</td>
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

      <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
        <p role="status" className="text-sm text-gray-600">
          {totalCount === 0
            ? "No matching entries"
            : `Showing ${firstOnPage}–${lastOnPage} of ${totalCount}`}
        </p>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => setPage((current) => Math.max(0, current - 1))}
            disabled={page === 0 || entries.isFetching}
            className="rounded-md border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-primary-500 disabled:cursor-not-allowed disabled:opacity-60"
          >
            Previous page
          </button>
          <span className="text-sm text-gray-600">Page {page + 1}</span>
          <button
            type="button"
            onClick={() => setPage((current) => current + 1)}
            disabled={!hasNextPage || entries.isFetching}
            className="rounded-md border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-primary-500 disabled:cursor-not-allowed disabled:opacity-60"
          >
            Next page
          </button>
        </div>
      </div>
    </div>
  );
}

export default AuditLogTable;
