"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { AlertCircle, Eye, EyeOff, Lock, Mail } from "lucide-react";
import { useAuth } from "@/context/AuthContext";
import { extractErrorMessage } from "@/lib/api";

const loginSchema = z.object({
  email: z.string().min(1, "Email is required").email("Enter a valid email address"),
  password: z.string().min(1, "Password is required"),
});

type LoginValues = z.infer<typeof loginSchema>;

export function LoginForm() {
  const { login, status } = useAuth();
  const router = useRouter();
  const [formError, setFormError] = useState<string | null>(null);
  const [showPassword, setShowPassword] = useState(false);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: "", password: "" },
  });

  useEffect(() => {
    if (status === "authenticated") router.replace("/dashboard");
  }, [status, router]);

  const onSubmit = async (values: LoginValues) => {
    setFormError(null);
    try {
      await login(values.email, values.password);
      router.replace("/dashboard");
    } catch (error) {
      setFormError(extractErrorMessage(error, "Sign in failed. Please try again."));
    }
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-5">
      {/* ── Error banner ────────────────────────────────────────────────────────── */}
      {formError ? (
        <div
          role="alert"
          className="flex items-start gap-2.5 rounded-xl border border-danger-200 bg-danger-50 px-3.5 py-3 text-sm text-danger-700"
        >
          <AlertCircle aria-hidden className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{formError}</span>
        </div>
      ) : null}

      {/* ── Email ──────────────────────────────────────────────────────────────── */}
      <div className="space-y-1.5">
        <label htmlFor="email" className="block text-sm font-medium text-gray-700">
          Email address
        </label>
        <div className="relative">
          <Mail
            aria-hidden
            className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400"
          />
          <input
            id="email"
            type="email"
            autoComplete="email"
            autoFocus
            aria-invalid={errors.email ? true : undefined}
            aria-describedby={errors.email ? "email-error" : undefined}
            className={`h-10 w-full rounded-xl border bg-gray-50 pl-9 pr-3.5 text-sm text-gray-900 transition-colors placeholder:text-gray-400 hover:border-gray-400 focus:bg-white focus:outline-none focus:ring-2 focus:ring-primary-500 ${
              errors.email ? "border-danger-500 bg-danger-50" : "border-gray-300"
            }`}
            placeholder="you@company.com"
            {...register("email")}
          />
        </div>
        {errors.email?.message ? (
          <p id="email-error" role="alert" className="text-xs text-danger-700">
            {errors.email.message}
          </p>
        ) : null}
      </div>

      {/* ── Password ────────────────────────────────────────────────────────────── */}
      <div className="space-y-1.5">
        <div className="flex items-baseline justify-between">
          <label htmlFor="password" className="block text-sm font-medium text-gray-700">
            Password
          </label>
          <Link
            href="/forgot-password"
            className="text-xs font-medium text-primary-600 hover:text-primary-700 hover:underline"
            tabIndex={-1}
          >
            Forgot password?
          </Link>
        </div>

        <div className="relative">
          <Lock
            aria-hidden
            className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400"
          />
          <input
            id="password"
            type={showPassword ? "text" : "password"}
            autoComplete="current-password"
            aria-invalid={errors.password ? true : undefined}
            aria-describedby={errors.password ? "password-error" : undefined}
            className={`h-10 w-full rounded-xl border bg-gray-50 pl-9 pr-10 text-sm text-gray-900 transition-colors placeholder:text-gray-400 hover:border-gray-400 focus:bg-white focus:outline-none focus:ring-2 focus:ring-primary-500 ${
              errors.password ? "border-danger-500 bg-danger-50" : "border-gray-300"
            }`}
            placeholder="••••••••"
            {...register("password")}
          />
          <button
            type="button"
            aria-label={showPassword ? "Hide password" : "Show password"}
            onClick={() => setShowPassword((prev) => !prev)}
            className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
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
      </div>

      {/* ── Submit ──────────────────────────────────────────────────────────────── */}
      <button
        type="submit"
        disabled={isSubmitting}
        aria-busy={isSubmitting}
        className="mt-1 flex h-10 w-full items-center justify-center gap-2 rounded-xl bg-primary-600 text-sm font-semibold text-white shadow-sm transition-all hover:bg-primary-700 active:bg-primary-800 disabled:cursor-not-allowed disabled:opacity-60"
      >
        {isSubmitting ? (
          <>
            <svg
              aria-hidden
              className="h-4 w-4 animate-spin"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
            >
              <path d="M21 12a9 9 0 1 1-6.219-8.56" />
            </svg>
            Signing in…
          </>
        ) : (
          "Sign in to FlowForge"
        )}
      </button>

      {/* ── Divider + hint ──────────────────────────────────────────────────────── */}
      <div className="relative flex items-center gap-3 pt-1">
        <span className="h-px flex-1 bg-gray-200" />
        <span className="text-xs text-gray-400">secure workspace login</span>
        <span className="h-px flex-1 bg-gray-200" />
      </div>

      <p className="text-center text-xs text-gray-400">
        Your session is encrypted end-to-end.{" "}
        <Link href="/forgot-password" className="text-primary-500 hover:underline">
          Need help signing in?
        </Link>
      </p>
    </form>
  );
}

export default LoginForm;
