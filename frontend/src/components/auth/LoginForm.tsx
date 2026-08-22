"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { AlertCircle, ArrowRight, Eye, EyeOff, Loader2, Lock, Mail, ShieldCheck } from "lucide-react";
import { useAuth } from "@/context/AuthContext";
import { extractErrorMessage } from "@/lib/api";

const loginSchema = z.object({
  email: z.string().min(1, "Email is required").email("Enter a valid email address"),
  // No length rule: sign-in must accept whatever the account already has, whatever the policy is today.
  password: z.string().min(1, "Password is required"),
});

type LoginValues = z.infer<typeof loginSchema>;

export function LoginForm() {
  const { login, status } = useAuth();
  const router = useRouter();
  const [formError, setFormError] = useState<string | null>(null);
  const [showPassword, setShowPassword] = useState(false);
  const [capsLockOn, setCapsLockOn] = useState(false);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: "", password: "" },
  });

  // Held separately so the Caps Lock reset can run alongside the form library's own blur handler
  // instead of replacing it — spreading `register(...)` last would silently drop ours, spreading it
  // first would drop theirs and with it the field's validation-on-blur.
  const passwordField = register("password");

  // Someone with a live session has no business on the login page.
  useEffect(() => {
    if (status === "authenticated") {
      router.replace("/dashboard");
    }
  }, [status, router]);

  const onSubmit = async (values: LoginValues) => {
    setFormError(null);
    try {
      await login(values.email, values.password);
      router.replace("/dashboard");
    } catch (error) {
      // The backend deliberately answers wrong password, unknown email and deactivated account with one
      // generic message so the endpoint cannot be used to discover which emails have accounts. It is
      // surfaced unchanged rather than being softened into something more helpful but less true.
      setFormError(extractErrorMessage(error, "Sign in failed. Please try again."));
    }
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-5">
      {formError ? (
        <div
          role="alert"
          className="flex animate-scale-in items-start gap-2.5 rounded-xl border border-danger-200 bg-danger-50 px-3.5 py-3 text-sm text-danger-800"
        >
          <AlertCircle aria-hidden className="mt-0.5 h-4 w-4 shrink-0 text-danger-600" />
          <span>{formError}</span>
        </div>
      ) : null}

      <Field
        id="email"
        label="Email address"
        icon={Mail}
        error={errors.email?.message}
        type="email"
        autoComplete="email"
        autoFocus
        placeholder="you@company.com"
        register={register("email")}
      />

      <div className="space-y-1.5">
        <div className="flex items-baseline justify-between gap-3">
          <label htmlFor="password" className="text-sm font-medium text-gray-700">
            Password
          </label>
          {/*
            Beside the field rather than after the submit button. This is the moment someone realises
            they do not remember it, and making them look past the primary action to find the way out is
            a small cruelty that costs support tickets.
          */}
          <Link
            href="/forgot-password"
            className="rounded text-xs font-medium text-primary-600 hover:text-primary-700 hover:underline"
          >
            Forgot password?
          </Link>
        </div>

        {/* `group` so the icon can tint when the input inside is focused. */}
        <div className="group relative">
          <Lock
            aria-hidden
            className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400 transition-colors group-focus-within:text-primary-600"
          />
          <input
            id="password"
            type={showPassword ? "text" : "password"}
            autoComplete="current-password"
            placeholder="Enter your password"
            aria-invalid={errors.password ? true : undefined}
            aria-describedby={
              [errors.password ? "password-error" : null, capsLockOn ? "password-caps" : null]
                .filter(Boolean)
                .join(" ") || undefined
            }
            // Caps Lock is read from the event rather than tracked as state of the world, because there
            // is no way to query it — only to observe it on a key event.
            onKeyUp={(event) => setCapsLockOn(event.getModifierState?.("CapsLock") ?? false)}
            className={`h-11 w-full rounded-xl border bg-gray-50/80 pl-9 pr-11 text-sm text-gray-900 transition-all placeholder:text-gray-400 hover:border-gray-400 focus:bg-white focus:shadow-xs ${
              errors.password ? "border-danger-400 bg-danger-50/60" : "border-gray-300"
            }`}
            {...passwordField}
            onBlur={(event) => {
              void passwordField.onBlur(event);
              setCapsLockOn(false);
            }}
          />
          <button
            type="button"
            onClick={() => setShowPassword((previous) => !previous)}
            // A control, so it is labelled and its state is announced rather than only drawn.
            aria-label={showPassword ? "Hide password" : "Show password"}
            aria-pressed={showPassword}
            className="absolute right-1.5 top-1/2 flex h-8 w-8 -translate-y-1/2 items-center justify-center rounded-lg text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600"
          >
            {showPassword ? (
              <EyeOff aria-hidden className="h-4 w-4" />
            ) : (
              <Eye aria-hidden className="h-4 w-4" />
            )}
          </button>
        </div>

        {errors.password?.message ? (
          <p id="password-error" role="alert" className="text-xs text-danger-700">
            {errors.password.message}
          </p>
        ) : null}

        {/*
          Caps Lock is a real cause of failed sign-ins, and because the field is masked there is nothing
          on screen to reveal it — the person simply gets told their password is wrong. Worth the few
          lines. `role="status"` rather than `alert`: it is a hint, not an error, and should not interrupt.
        */}
        {capsLockOn && !errors.password ? (
          <p
            id="password-caps"
            role="status"
            className="flex items-center gap-1.5 text-xs text-warning-700"
          >
            <AlertCircle aria-hidden className="h-3.5 w-3.5" />
            Caps Lock is on
          </p>
        ) : null}
      </div>

      <button
        type="submit"
        disabled={isSubmitting}
        aria-busy={isSubmitting}
        className="group relative flex h-11 w-full items-center justify-center gap-2 overflow-hidden rounded-xl bg-primary-600 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-primary-700 active:bg-primary-800 disabled:cursor-not-allowed disabled:bg-primary-400"
      >
        {isSubmitting ? (
          <>
            <Loader2 aria-hidden className="h-4 w-4 animate-spin" />
            Signing in…
          </>
        ) : (
          <>
            Sign in
            {/* Nudges forward on hover — a small confirmation that the control is live. */}
            <ArrowRight
              aria-hidden
              className="h-4 w-4 transition-transform group-hover:translate-x-0.5"
            />
          </>
        )}
      </button>

      <p className="flex items-center justify-center gap-1.5 pt-1 text-xs text-gray-400">
        <ShieldCheck aria-hidden className="h-3.5 w-3.5" />
        Session secured with rotating tokens
      </p>
    </form>
  );
}

/**
 * A labelled input with a leading icon.
 *
 * Only the email field uses it — password has its own markup for the reveal button and the Caps Lock
 * hint — but it keeps the two fields visually identical, which is the part that would drift if both were
 * written out by hand.
 */
function Field({
  id,
  label,
  icon: Icon,
  error,
  register,
  ...inputProps
}: {
  id: string;
  label: string;
  icon: typeof Mail;
  error?: string;
  register: ReturnType<ReturnType<typeof useForm<LoginValues>>["register"]>;
} & React.InputHTMLAttributes<HTMLInputElement>) {
  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="block text-sm font-medium text-gray-700">
        {label}
      </label>
      <div className="group relative">
        <Icon
          aria-hidden
          className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400 transition-colors group-focus-within:text-primary-600"
        />
        <input
          id={id}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${id}-error` : undefined}
          className={`h-11 w-full rounded-xl border bg-gray-50/80 pl-9 pr-3.5 text-sm text-gray-900 transition-all placeholder:text-gray-400 hover:border-gray-400 focus:bg-white focus:shadow-xs ${
            error ? "border-danger-400 bg-danger-50/60" : "border-gray-300"
          }`}
          {...inputProps}
          {...register}
        />
      </div>
      {error ? (
        <p id={`${id}-error`} role="alert" className="text-xs text-danger-700">
          {error}
        </p>
      ) : null}
    </div>
  );
}

export default LoginForm;
