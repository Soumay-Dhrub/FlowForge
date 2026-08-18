"use client";

/**
 * Profile edit form: name, department, and — for an administrator — role.
 *
 * The role selector is rendered only for an ADMIN, and not because it would look untidy otherwise:
 * `PATCH /api/users/{id}` refuses a self-edit that carries a `roleId` (403), which is what stops a
 * user promoting themselves. Showing the control to someone whose submission would be rejected would
 * be offering an action that cannot succeed, so a self-editing non-admin gets name and department and
 * an explanation of why their role is fixed.
 *
 * Only changed fields are sent, matching the endpoint's PATCH semantics.
 */
import { useEffect } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Loader2 } from "lucide-react";
import { z } from "zod";
import { extractErrorMessage, isForbiddenError, isStatusError } from "@/lib/api";
import { fetchDepartments, fetchRoles, referenceDataKeys } from "@/lib/referenceDataApi";
import { fetchUser, updateUser, userKeys } from "@/lib/usersApi";
import { useAuth } from "@/context/AuthContext";
import NotAuthorized from "@/components/ui/NotAuthorized";
import SelectField from "@/components/ui/SelectField";
import SubmitButton from "@/components/ui/SubmitButton";
import TextField from "@/components/ui/TextField";
import type { UpdateUserInput } from "@/lib/usersApi";

const schema = z.object({
  name: z.string().trim().min(1, "Name is required").max(150, "Name must not exceed 150 characters"),
  roleId: z.string(),
  departmentId: z.string(),
});

type FormValues = z.infer<typeof schema>;

export function UserProfileForm({ userId }: { userId: string }) {
  const { user: caller } = useAuth();
  const queryClient = useQueryClient();
  const isAdmin = caller?.roleName === "ADMIN";
  const isSelf = caller?.id === userId;

  const profile = useQuery({ queryKey: userKeys.detail(userId), queryFn: () => fetchUser(userId) });
  const roles = useQuery({
    queryKey: referenceDataKeys.roles,
    queryFn: fetchRoles,
    enabled: isAdmin,
  });
  const departments = useQuery({
    queryKey: referenceDataKeys.departments,
    queryFn: fetchDepartments,
  });

  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { name: "", roleId: "", departmentId: "" },
  });

  // The form is populated once the profile arrives, not on every render, so a half-typed edit is
  // never overwritten by a background refetch.
  useEffect(() => {
    if (profile.data) {
      reset({
        name: profile.data.name,
        roleId: profile.data.roleId,
        departmentId: profile.data.departmentId ?? "",
      });
    }
  }, [profile.data, reset]);

  const mutation = useMutation({
    mutationFn: (input: UpdateUserInput) => updateUser(userId, input),
    onSuccess: (updated) => {
      queryClient.invalidateQueries({ queryKey: userKeys.all });
      reset({
        name: updated.name,
        roleId: updated.roleId,
        departmentId: updated.departmentId ?? "",
      });
    },
  });

  if (profile.isError && isForbiddenError(profile.error)) {
    return <NotAuthorized message="You can only view your own profile." />;
  }

  if (profile.isPending) {
    return (
      <p role="status" className="inline-flex items-center gap-2 text-sm text-gray-600">
        <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
        Loading profile…
      </p>
    );
  }

  if (profile.isError) {
    const missing = isStatusError(profile.error, 404);
    return (
      <div className="mx-auto max-w-md text-center">
        <p role="alert" className="text-sm text-danger-700">
          {missing
            ? "That user does not exist."
            : extractErrorMessage(profile.error, "Could not load this profile.")}
        </p>
        <Link href="/users" className="mt-3 inline-block text-sm text-primary-700 hover:underline">
          Back to users
        </Link>
      </div>
    );
  }

  const onSubmit = handleSubmit(async (values) => {
    const changes: UpdateUserInput = {};
    if (values.name !== profile.data.name) {
      changes.name = values.name;
    }
    if (values.departmentId && values.departmentId !== profile.data.departmentId) {
      changes.departmentId = values.departmentId;
    }
    // Never send roleId on a self-edit: the endpoint rejects it, deliberately.
    if (isAdmin && !isSelf && values.roleId && values.roleId !== profile.data.roleId) {
      changes.roleId = values.roleId;
    }

    if (Object.keys(changes).length === 0) {
      return;
    }

    try {
      await mutation.mutateAsync(changes);
    } catch (error) {
      setError("root", {
        message: extractErrorMessage(error, "Could not save these changes. Try again."),
      });
    }
  });

  const canEditRole = isAdmin && !isSelf;

  return (
    <div className="mx-auto max-w-lg">
      <Link href="/users" className="text-sm text-primary-700 hover:underline">
        ← Users
      </Link>
      <h1 className="mt-2 text-2xl font-bold text-primary-700">{profile.data.name}</h1>
      <p className="mt-1 text-sm text-gray-600">
        {profile.data.email} · {profile.data.isActive ? "Active" : "Inactive"}
      </p>

      <form onSubmit={onSubmit} noValidate className="mt-6 space-y-4">
        <TextField
          id="profile-name"
          label="Name"
          autoComplete="name"
          error={errors.name?.message}
          {...register("name")}
        />

        <SelectField
          id="profile-role"
          label="Role"
          disabled={!canEditRole || roles.isPending}
          hint={
            canEditRole
              ? undefined
              : "Your own role can only be changed by another administrator."
          }
          error={errors.roleId?.message}
          {...register("roleId")}
        >
          {canEditRole ? null : (
            <option value={profile.data.roleId}>{profile.data.roleName}</option>
          )}
          {(roles.data ?? []).map((role) => (
            <option key={role.id} value={role.id}>
              {role.name}
            </option>
          ))}
        </SelectField>

        <SelectField
          id="profile-department"
          label="Department"
          disabled={departments.isPending || departments.isError}
          error={errors.departmentId?.message}
          {...register("departmentId")}
        >
          <option value="">
            {departments.isPending ? "Loading departments…" : "No department"}
          </option>
          {(departments.data ?? []).map((department) => (
            <option key={department.id} value={department.id}>
              {department.name}
            </option>
          ))}
        </SelectField>

        {errors.root?.message ? (
          <p role="alert" className="text-sm text-danger-700">
            {errors.root.message}
          </p>
        ) : null}
        {mutation.isSuccess && !isDirty ? (
          <p role="status" className="text-sm text-green-700">
            Profile saved.
          </p>
        ) : null}

        <SubmitButton isSubmitting={isSubmitting} pendingLabel="Saving…">
          Save changes
        </SubmitButton>
      </form>
    </div>
  );
}

export default UserProfileForm;
