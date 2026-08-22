import { Workflow } from "lucide-react";
import AuthShowcase from "@/components/auth/AuthShowcase";

/**
 * The frame around the unauthenticated pages: an illustrated panel on the left, the form on the right.
 *
 * The split is a real division of labour rather than decoration. The left panel answers "what is this
 * and why am I signing in" with a working pipeline diagram; the right panel does one job and is the only
 * thing on screen below `lg`, where the form should own the viewport rather than sit under half a metre
 * of artwork.
 *
 * The form column is what receives focus and what a screen reader encounters, because the panel is
 * `aria-hidden` — a described diagram would be noise between the page title and the email field.
 */
export function AuthCard({
  title,
  description,
  children,
  footer,
}: {
  title: string;
  description?: string;
  children: React.ReactNode;
  /** Secondary links — "back to sign in" and the like. */
  footer?: React.ReactNode;
}) {
  return (
    <main className="flex min-h-screen bg-white">
      <AuthShowcase />

      <div className="relative flex flex-1 flex-col items-center justify-center px-4 py-12 sm:px-8">
        {/* A faint wash, only where the illustrated panel is absent, so the narrow layout is not a bare
            white field. */}
        <div
          aria-hidden
          className="pointer-events-none absolute -top-32 left-1/2 h-72 w-[36rem] -translate-x-1/2 rounded-full bg-primary-100/40 blur-3xl lg:hidden"
        />

        <div className="relative w-full max-w-sm">
          {/* The mark repeats here because the left panel is gone below `lg`, and a login form with no
              indication of what it belongs to is unsettling when people arrive from a link in an email. */}
          <div className="mb-8 flex items-center gap-2.5 lg:hidden">
            <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-primary-600 text-white shadow-sm">
              <Workflow aria-hidden className="h-5 w-5" />
            </span>
            <span className="text-xl font-semibold tracking-tight text-gray-900">FlowForge</span>
          </div>

          <h1 className="text-2xl font-semibold tracking-tight text-gray-900">{title}</h1>
          {description ? <p className="mt-1.5 text-sm text-gray-500">{description}</p> : null}

          <div className="mt-7">{children}</div>

          {footer ? <div className="mt-6 text-sm text-gray-500">{footer}</div> : null}
        </div>
      </div>
    </main>
  );
}

export default AuthCard;
