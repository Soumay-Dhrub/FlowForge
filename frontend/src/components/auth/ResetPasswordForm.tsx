"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { extractErrorMessage } from "@/lib/api";
import { confirmPasswordReset } from "@/lib/authApi";
import SubmitButton from "@/components/ui/SubmitButton";
import TextField from "@/components/ui/TextField";

// Mirrors the backend constraint on PasswordResetConfirmRequest.newPassword.
const resetPasswordSchema = z
  .object({
    newPassword: z.string().min(8, "Password must be at least 8 characters"),
    confirmPassword: z.string().min(1, "Confirm your new password"),
  })
  .refine((values) => values.newPassword === values.confirmPassword, {
    path: ["confirmPassword"],
    message: "Passwords do not match",
  });

type ResetPasswordValues = z.infer<typeof resetPasswordSchema>;

export function ResetPasswordForm() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const token = searchParams.get("token") ?? "";
  const [formError, setFormError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<ResetPasswordValues>({
    resolver: zodResolver(resetPasswordSchema),
    defaultValues: { newPassword: "", confirmPassword: "" },
  });

  const onSubmit = async (values: ResetPasswordValues) => {
    setFormError(null);
    try {
      await confirmPasswordReset(token, values.newPassword);
      setDone(true);
    } catch (error) {
      // 400 covers unknown, expired and already-used tokens; the backend message says which.
      setFormError(extractErrorMessage(error, "Could not reset your password. Please try again."));
    }
  };

  if (!token) {
    return (
      <div className="space-y-4">
        <div
          role="alert"
          className="rounded-md border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-700"
        >
          This reset link is missing its token. Request a new link to continue.
        </div>
        <Link href="/forgot-password" className="text-sm text-primary-600 hover:underline">
          Request a new reset link
        </Link>
      </div>
    );
  }

  if (done) {
    return (
      <div className="space-y-4">
        <div
          role="status"
          className="rounded-md border border-primary-100 bg-primary-50 px-3 py-2 text-sm text-gray-700"
        >
          Your password has been updated. You can sign in with it now.
        </div>
        <button
          type="button"
          onClick={() => router.replace("/login")}
          className="w-full rounded-md bg-primary-600 px-4 py-2 font-medium text-white hover:bg-primary-700 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2"
        >
          Go to sign in
        </button>
      </div>
    );
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-4">
      {formError ? (
        <div
          role="alert"
          className="rounded-md border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-700"
        >
          {formError}
        </div>
      ) : null}

      <TextField
        id="newPassword"
        label="New password"
        type="password"
        autoComplete="new-password"
        hint="At least 8 characters."
        error={errors.newPassword?.message}
        {...register("newPassword")}
      />

      <TextField
        id="confirmPassword"
        label="Confirm new password"
        type="password"
        autoComplete="new-password"
        error={errors.confirmPassword?.message}
        {...register("confirmPassword")}
      />

      <SubmitButton isSubmitting={isSubmitting} pendingLabel="Updating…">
        Update password
      </SubmitButton>
    </form>
  );
}

export default ResetPasswordForm;
