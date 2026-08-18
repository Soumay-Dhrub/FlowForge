"use client";

/**
 * Create-user dialog (Requirement 1.1).
 *
 * The role and department options come from `GET /api/roles` and `GET /api/departments`, so nothing
 * here hard-codes a seeded UUID. Client-side validation mirrors the server's constraints — name,
 * valid email, password of at least 8 characters, a role and a department — so the obvious mistakes
 * are caught before a round trip, but the server remains the authority.
 *
 * A duplicate email comes back as 409 and is reported *on the email field*, because that is the one
 * field the person has to change. Everything else surfaces as a form-level error.
 */
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { extractErrorMessage, isStatusError } from "@/lib/api";
import { fetchDepartments, fetchRoles, referenceDataKeys } from "@/lib/referenceDataApi";
import { createUser, userKeys } from "@/lib/usersApi";
import Modal from "@/components/ui/Modal";
import SelectField from "@/components/ui/SelectField";
import SubmitButton from "@/components/ui/SubmitButton";
import TextField from "@/components/ui/TextField";
import type { User } from "@/types";

const schema = z.object({
  name: z.string().trim().min(1, "Name is required").max(150, "Name must not exceed 150 characters"),
  email: z.string().trim().min(1, "Email is required").email("Enter a valid email address"),
  password: z.string().min(8, "Password must be at least 8 characters"),
  roleId: z.string().min(1, "Select a role"),
  departmentId: z.string().min(1, "Select a department"),
});

type FormValues = z.infer<typeof schema>;

interface CreateUserModalProps {
  open: boolean;
  onClose: () => void;
  onCreated?: (user: User) => void;
}

export function CreateUserModal({ open, onClose, onCreated }: CreateUserModalProps) {
  const queryClient = useQueryClient();
  const roles = useQuery({ queryKey: referenceDataKeys.roles, queryFn: fetchRoles, enabled: open });
  const departments = useQuery({
    queryKey: referenceDataKeys.departments,
    queryFn: fetchDepartments,
    enabled: open,
  });

  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { name: "", email: "", password: "", roleId: "", departmentId: "" },
  });

  const mutation = useMutation({
    mutationFn: createUser,
    onSuccess: (created) => {
      queryClient.invalidateQueries({ queryKey: userKeys.all });
      reset();
      onCreated?.(created);
      onClose();
    },
  });

  const optionsFailed = roles.isError || departments.isError;

  const onSubmit = handleSubmit(async (values) => {
    try {
      await mutation.mutateAsync(values);
    } catch (error) {
      if (isStatusError(error, 409)) {
        setError("email", {
          message: extractErrorMessage(error, "That email is already registered."),
        });
        return;
      }
      setError("root", {
        message: extractErrorMessage(error, "Could not create the user. Try again."),
      });
    }
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="New user"
      description="The account is active as soon as it is created."
    >
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <TextField
          id="new-user-name"
          label="Name"
          autoComplete="off"
          error={errors.name?.message}
          {...register("name")}
        />
        <TextField
          id="new-user-email"
          label="Email"
          type="email"
          autoComplete="off"
          error={errors.email?.message}
          {...register("email")}
        />
        <TextField
          id="new-user-password"
          label="Temporary password"
          type="password"
          autoComplete="new-password"
          hint="At least 8 characters."
          error={errors.password?.message}
          {...register("password")}
        />
        <SelectField
          id="new-user-role"
          label="Role"
          disabled={roles.isPending || roles.isError}
          error={errors.roleId?.message}
          {...register("roleId")}
        >
          <option value="">{roles.isPending ? "Loading roles…" : "Select a role"}</option>
          {(roles.data ?? []).map((role) => (
            <option key={role.id} value={role.id}>
              {role.name}
            </option>
          ))}
        </SelectField>
        <SelectField
          id="new-user-department"
          label="Department"
          disabled={departments.isPending || departments.isError}
          error={errors.departmentId?.message}
          {...register("departmentId")}
        >
          <option value="">
            {departments.isPending ? "Loading departments…" : "Select a department"}
          </option>
          {(departments.data ?? []).map((department) => (
            <option key={department.id} value={department.id}>
              {department.name}
            </option>
          ))}
        </SelectField>

        {optionsFailed ? (
          <p role="alert" className="text-sm text-danger-700">
            Could not load the role and department options. Close this dialog and try again.
          </p>
        ) : null}
        {errors.root?.message ? (
          <p role="alert" className="text-sm text-danger-700">
            {errors.root.message}
          </p>
        ) : null}

        <SubmitButton isSubmitting={isSubmitting} pendingLabel="Creating…">
          Create user
        </SubmitButton>
      </form>
    </Modal>
  );
}

export default CreateUserModal;
