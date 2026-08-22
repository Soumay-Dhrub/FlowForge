import { Workflow } from "lucide-react";
import AuthShowcase from "@/components/auth/AuthShowcase";

export function AuthCard({
  title,
  description,
  children,
  footer,
}: {
  title: string;
  description?: string;
  children: React.ReactNode;
  footer?: React.ReactNode;
}) {
  return (
    <main className="flex min-h-screen bg-white">
      <AuthShowcase />

      {/* ─── Right column ─────────────────────────────────────────────────────── */}
      <div className="relative flex flex-1 flex-col items-center justify-center bg-gray-50/60 px-4 py-12 sm:px-10">
        {/* Soft ambient wash – visible on mobile where the panel is hidden */}
        <div
          aria-hidden
          className="pointer-events-none absolute -top-40 left-1/2 h-80 w-[40rem] -translate-x-1/2 rounded-full bg-primary-100/50 blur-3xl lg:hidden"
        />

        <div className="relative w-full max-w-[360px]">
          {/* Mobile wordmark */}
          <div className="mb-9 flex items-center gap-2.5 lg:hidden">
            <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-primary-600 text-white shadow-sm">
              <Workflow aria-hidden className="h-5 w-5" />
            </span>
            <span className="text-xl font-semibold tracking-tight text-gray-900">FlowForge</span>
          </div>

          {/* The card that holds the form */}
          <div className="rounded-2xl border border-gray-200 bg-white px-7 py-8 shadow-md">
            <h1 className="text-xl font-semibold tracking-tight text-gray-900">{title}</h1>
            {description ? (
              <p className="mt-1 text-sm text-gray-500">{description}</p>
            ) : null}
            <div className="mt-7">{children}</div>
          </div>

          {footer ? (
            <div className="mt-5 text-center text-sm text-gray-500">{footer}</div>
          ) : null}
        </div>
      </div>
    </main>
  );
}

export default AuthCard;
