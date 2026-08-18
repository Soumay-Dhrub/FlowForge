"use client";

/**
 * The user table (Requirements 1.1, 4.1, 4.3). ADMIN only.
 *
 * Access is checked twice, on purpose. The caller's own role short-circuits the page so a MANAGER who
 * types `/users` gets a plain "not authorized" panel instead of a table that fails; and a 403 from
 * the API lands on the same panel, which covers the case where the client's idea of the role is
 * stale or wrong. Neither is the security boundary — the endpoint is (Requirement 3.2) — they just
 * make the refusal legible.
 */
import { useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, Plus } from "lucide-react";
import { extractErrorMessage, isForbiddenError } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { fetchUsers, setUserStatus, userKeys } from "@/lib/usersApi";
import { useAuth } from "@/context/AuthContext";
import NotAuthorized from "@/components/ui/NotAuthorized";
import CreateUserModal from "@/components/users/CreateUserModal";
import type { User } from "@/types";

const ADMIN_ONLY_MESSAGE = "Only administrators can manage users.";

export function UserList() {
  const { user: caller, status } = useAuth();
  const queryClient = useQueryClient();
  const [createOpen, setCreateOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const isAdmin = caller?.roleName === "ADMIN";

  const users = useQuery({
    queryKey: userKeys.list,
    queryFn: fetchUsers,
    // No point asking for a list the caller is not allowed to have.
    enabled: isAdmin,
  });

  const toggleStatus = useMutation({
    mutationFn: (target: User) => setUserStatus(target.id, !target.isActive),
    onSuccess: (updated) => {
      setActionError(null);
      setNotice(
        updated.isActive
          ? `Reactivated ${updated.name}. They can sign in again.`
          : `Deactivated ${updated.name}. Their active sessions have been revoked.`,
      );
      queryClient.invalidateQueries({ queryKey: userKeys.all });
    },
    onError: (error) => {
      setNotice(null);
      setActionError(extractErrorMessage(error, "Could not change that account's status."));
    },
  });

  if (status === "authenticated" && !isAdmin) {
    return <NotAuthorized message={ADMIN_ONLY_MESSAGE} />;
  }

  if (users.isError && isForbiddenError(users.error)) {
    return <NotAuthorized message={ADMIN_ONLY_MESSAGE} />;
  }

  const rows = users.data ?? [];

  return (
    <div className="mx-auto max-w-5xl">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-primary-700">Users</h1>
          <p className="mt-1 text-sm text-gray-600">
            Provision accounts, change roles and departments, and deactivate access.
          </p>
        </div>
        <button
          type="button"
          onClick={() => setCreateOpen(true)}
          className="inline-flex items-center gap-2 rounded-md bg-primary-600 px-3 py-2 text-sm font-medium text-white hover:bg-primary-700 focus:ring-offset-2"
        >
          <Plus aria-hidden="true" className="h-4 w-4" />
          New user
        </button>
      </div>

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

      <div className="mt-4 overflow-hidden rounded-xl border border-gray-200 bg-white">
        <table className="min-w-full divide-y divide-gray-200 text-sm">
          <caption className="sr-only">Users, newest first</caption>
          <thead className="bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500">
            <tr>
              <th scope="col" className="px-4 py-3">
                Name
              </th>
              <th scope="col" className="px-4 py-3">
                Email
              </th>
              <th scope="col" className="px-4 py-3">
                Role
              </th>
              <th scope="col" className="px-4 py-3">
                Department
              </th>
              <th scope="col" className="px-4 py-3">
                Status
              </th>
              <th scope="col" className="px-4 py-3">
                Created
              </th>
              <th scope="col" className="px-4 py-3">
                Actions
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200">
            {users.isPending ? (
              <tr>
                <td colSpan={7} className="px-4 py-8 text-center text-gray-600">
                  <span role="status" className="inline-flex items-center gap-2">
                    <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
                    Loading users…
                  </span>
                </td>
              </tr>
            ) : null}

            {users.isError ? (
              <tr>
                <td colSpan={7} className="px-4 py-8 text-center">
                  <p role="alert" className="text-sm text-danger-700">
                    {extractErrorMessage(users.error, "Could not load users.")}
                  </p>
                  <button
                    type="button"
                    onClick={() => users.refetch()}
                    className="mt-3 rounded-md border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50"
                  >
                    Try again
                  </button>
                </td>
              </tr>
            ) : null}

            {users.isSuccess && rows.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-4 py-8 text-center text-sm text-gray-600">
                  No users yet.
                </td>
              </tr>
            ) : null}

            {rows.map((row) => {
              const pending = toggleStatus.isPending && toggleStatus.variables?.id === row.id;
              return (
                <tr key={row.id} className="hover:bg-gray-50">
                  <th scope="row" className="px-4 py-3 text-left font-medium text-gray-900">
                    <Link
                      href={`/users/${row.id}`}
                      className="text-primary-700 hover:underline"
                    >
                      {row.name}
                    </Link>
                  </th>
                  <td className="px-4 py-3 text-gray-700">{row.email}</td>
                  <td className="px-4 py-3 text-gray-700">{row.roleName}</td>
                  <td className="px-4 py-3 text-gray-700">{row.departmentName ?? "—"}</td>
                  <td className="px-4 py-3">
                    <span
                      className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium ${
                        row.isActive ? "bg-green-100 text-success-800" : "bg-gray-200 text-gray-700"
                      }`}
                    >
                      {row.isActive ? "Active" : "Inactive"}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-gray-700">{formatDateTime(row.createdAt)}</td>
                  <td className="px-4 py-3">
                    <button
                      type="button"
                      onClick={() => toggleStatus.mutate(row)}
                      disabled={toggleStatus.isPending}
                      aria-busy={pending}
                      className="rounded-md border border-gray-300 px-2.5 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-60"
                    >
                      {pending
                        ? "Saving…"
                        : row.isActive
                          ? `Deactivate ${row.name}`
                          : `Reactivate ${row.name}`}
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <CreateUserModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={(created) => setNotice(`Created ${created.name}.`)}
      />
    </div>
  );
}

export default UserList;
