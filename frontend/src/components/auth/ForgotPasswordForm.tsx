"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { extractErrorMessage } from "@/lib/api";
import { requestPasswordReset } from "@/lib/authApi";
import SubmitButton from "@/components/ui/SubmitButton";
import TextField from "@/components/ui/TextField";

const forgotPasswordSchema = z.object({
  email: z.string().min(1, "Email is required").email("Enter a valid email address"),
});

type ForgotPasswordValues = z.infer<typeof forgotPasswordSchema>;

export function ForgotPasswordForm() {
  const [notice, setNotice] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<ForgotPasswordValues>({
    resolver: zodResolver(forgotPasswordSchema),
    defaultValues: { email: "" },
  });

  const onSubmit = async (values: ForgotPasswordValues) => {
    setFormError(null);
    setNotice(null);
    try {
      // The endpoint answers 200 with the same neutral message either way, so the UI must not
      // add anything that would reveal whether the address is registered.
      setNotice(await requestPasswordReset(values.email));
    } catch (error) {
      setFormError(extractErrorMessage(error, "Could not submit the request. Please try again."));
    }
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-4">
      {notice ? (
        <div
          role="status"
          className="rounded-md border border-primary-100 bg-primary-50 px-3 py-2 text-sm text-gray-700"
        >
          {notice}
        </div>
      ) : null}

      {formError ? (
        <div
          role="alert"
          className="rounded-md border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-700"
        >
          {formError}
        </div>
      ) : null}

      <TextField
        id="email"
        label="Email"
        type="email"
        autoComplete="email"
        error={errors.email?.message}
        {...register("email")}
      />

      <SubmitButton isSubmitting={isSubmitting} pendingLabel="Sending…">
        Send reset link
      </SubmitButton>

      <p className="text-center text-sm text-gray-600">
        <Link href="/login" className="text-primary-600 hover:underline">
          Back to sign in
        </Link>
      </p>
    </form>
  );
}

export default ForgotPasswordForm;
