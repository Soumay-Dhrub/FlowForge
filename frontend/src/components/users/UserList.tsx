"use client";

import { useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertCircle, Plus, UserPlus, Users as UsersIcon } from "lucide-react";
import { extractErrorMessage, isForbiddenError } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { fetchUsers, setUserStatus, userKeys } from "@/lib/usersApi";
import { useAuth } from "@/context/AuthContext";
import Badge from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import Card from "@/components/ui/Card";
import EmptyState from "@/components/ui/EmptyState";
import NotAuthorized from "@/components/ui/NotAuthorized";
import PageHeader from "@/components/ui/PageHeader";
import { SkeletonTableRows } from "@/components/ui/Skeleton";
import CreateUserModal from "@/components/users/CreateUserModal";
import type { User } from "@/types";

const ADMIN_ONLY_MESSAGE = "Only administrators can manage users.";

const COLUMNS = ["Name", "Email", "Role", "Department", "Status", "Created", "Actions"] as const;

const ROLE_TONES = {
  ADMIN: "accent",
  MANAGER: "info",
  EMPLOYEE: "neutral",
} as const;

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
  const activeCount = rows.filter((row) => row.isActive).length;

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader
        title="Users"
        description="Provision accounts, change roles and departments, and deactivate access."
        actions={
          <Button variant="primary" icon={Plus} onClick={() => setCreateOpen(true)}>
            New user
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
      {actionError ? (
        <p
          role="alert"
          className="mb-4 flex items-start gap-2 animate-scale-in rounded-xl border border-danger-200 bg-danger-50 px-3.5 py-2.5 text-sm text-danger-800"
        >
          <AlertCircle aria-hidden className="mt-0.5 h-4 w-4 shrink-0 text-danger-600" />
          {actionError}
        </p>
      ) : null}

      <Card padded={false} className="overflow-hidden">
        {/*
          A count rather than a second heading. On a table this size the useful question is "how many of
          these can actually sign in", and deactivated accounts are invisible in a scan of 40 rows.
        */}
        <div className="flex items-center justify-between gap-3 border-b border-gray-200 bg-gray-50/70 px-4 py-2.5">
          <h2 className="text-sm font-semibold text-gray-900">All accounts</h2>
          {users.isSuccess && rows.length > 0 ? (
            <p className="text-xs text-gray-500">
              {activeCount} active of {rows.length}
            </p>
          ) : null}
        </div>

        <div className="overflow-x-auto">
          <table className="min-w-full divide-y divide-gray-200 text-sm">
            <caption className="sr-only">Users, newest first</caption>
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
              {users.isPending ? (
                <SkeletonTableRows rows={5} columns={COLUMNS.length} label="Loading users" />
              ) : null}

              {users.isError ? (
                <tr>
                  <td colSpan={COLUMNS.length} className="px-4 py-10 text-center">
                    <p role="alert" className="text-sm text-danger-700">
                      {extractErrorMessage(users.error, "Could not load users.")}
                    </p>
                    <Button className="mt-3" onClick={() => users.refetch()}>
                      Try again
                    </Button>
                  </td>
                </tr>
              ) : null}

              {users.isSuccess && rows.length === 0 ? (
                <tr>
                  <td colSpan={COLUMNS.length}>
                    {/*
                      `filtered={false}`: this table has no filters, so an empty result can only mean the
                      system has no accounts — which for an admin is an invitation, not a dead end.
                    */}
                    <EmptyState
                      filtered={false}
                      icon={UsersIcon}
                      title="No users yet."
                      description="Accounts you create here can sign in, own tasks, and approve work."
                      action={
                        <Button variant="primary" icon={UserPlus} onClick={() => setCreateOpen(true)}>
                          Create the first user
                        </Button>
                      }
                    />
                  </td>
                </tr>
              ) : null}

              {rows.map((row) => {
                const pending = toggleStatus.isPending && toggleStatus.variables?.id === row.id;
                return (
                  <tr key={row.id} className="transition-colors hover:bg-gray-50/80">
                    <th scope="row" className="px-4 py-3 text-left font-medium">
                      <Link
                        href={`/users/${row.id}`}
                        className="rounded font-medium text-primary-700 hover:text-primary-800 hover:underline"
                      >
                        {row.name}
                      </Link>
                    </th>
                    <td className="px-4 py-3 text-gray-600">{row.email}</td>
                    <td className="px-4 py-3">
                      <Badge tone={ROLE_TONES[row.roleName as keyof typeof ROLE_TONES] ?? "neutral"}>
                        {row.roleName}
                      </Badge>
                    </td>
                    <td className="px-4 py-3 text-gray-600">{row.departmentName ?? "—"}</td>
                    <td className="px-4 py-3">
                      <Badge tone={row.isActive ? "success" : "neutral"}>
                        {row.isActive ? "Active" : "Inactive"}
                      </Badge>
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 text-gray-600">
                      <time dateTime={row.createdAt}>{formatDateTime(row.createdAt)}</time>
                    </td>
                    <td className="px-4 py-3">
                      {/*
                        The full name is in the accessible label because "Deactivate" repeated down a
                        column tells a screen reader user nothing about which account they are about to
                        cut off. `loading` rather than `disabled` keeps focus on the button they pressed.
                      */}
                      <Button
                        size="sm"
                        variant={row.isActive ? "secondary" : "primary"}
                        loading={pending}
                        loadingLabel="Saving…"
                        onClick={() => toggleStatus.mutate(row)}
                      >
                        {row.isActive ? `Deactivate ${row.name}` : `Reactivate ${row.name}`}
                      </Button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </Card>

      <CreateUserModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={(created) => setNotice(`Created ${created.name}.`)}
      />
    </div>
  );
}

export default UserList;
