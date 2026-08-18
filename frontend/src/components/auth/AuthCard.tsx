import { Workflow } from "lucide-react";

/**
 * The frame around the unauthenticated pages.
 *
 * This is the first thing anyone sees, and it was a plain white box on a grey field. It now carries the
 * mark and a line of context, because a login screen with no indication of what it belongs to is
 * unsettling on an internal tool where people arrive from a link in an email.
 *
 * The card is centred but sits slightly above the optical middle: a form pinned to the exact centre
 * reads as low, because the eye weights the space beneath it more heavily than the space above.
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
    <main className="relative flex min-h-screen flex-col items-center justify-center overflow-hidden bg-gray-50 px-4 py-12">
      {/*
        A single soft wash behind the card. Decorative only, hence aria-hidden: it gives the page some
        depth without becoming a background image that fights the form for attention.
      */}
      <div
        aria-hidden
        className="pointer-events-none absolute -top-40 left-1/2 h-80 w-[42rem] -translate-x-1/2 rounded-full bg-primary-100/50 blur-3xl"
      />

      <div className="relative w-full max-w-sm">
        <div className="mb-6 flex items-center justify-center gap-2.5">
          <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-primary-600 text-white shadow-sm">
            <Workflow aria-hidden className="h-5 w-5" />
          </span>
          <span className="text-xl font-semibold tracking-tight text-gray-900">FlowForge</span>
        </div>

        <div className="rounded-xl border border-gray-200 bg-white p-6 shadow-md">
          <h1 className="text-lg font-semibold text-gray-900">{title}</h1>
          {description ? <p className="mt-1 text-sm text-gray-500">{description}</p> : null}
          <div className="mt-6">{children}</div>
        </div>

        {footer ? <div className="mt-4 text-center text-sm text-gray-500">{footer}</div> : null}

        <p className="mt-8 text-center text-xs text-gray-400">
          Workflow orchestration for internal approvals
        </p>
      </div>
    </main>
  );
}

export default AuthCard;
