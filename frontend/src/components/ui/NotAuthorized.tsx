"use client";

/**
 * The page-level "you cannot see this" state.
 *
 * Shown when the caller's role has no access to a page, whether that was known up front from their
 * profile or learned from a 403. It is a plain explanation with a way out, not an error: hiding the
 * navigation link is not protection, and a raw 403 body is not an answer.
 */
import Link from "next/link";
import { ShieldAlert } from "lucide-react";

export function NotAuthorized({
  message = "You do not have permission to view this page.",
}: {
  message?: string;
}) {
  return (
    <div
      role="alert"
      className="mx-auto max-w-md rounded-lg border border-amber-200 bg-amber-50 p-6 text-center"
    >
      <ShieldAlert aria-hidden="true" className="mx-auto h-8 w-8 text-amber-600" />
      <h1 className="mt-3 text-lg font-semibold text-amber-900">Not authorized</h1>
      <p className="mt-1 text-sm text-amber-800">{message}</p>
      <Link
        href="/dashboard"
        className="mt-4 inline-block rounded-md bg-white px-3 py-2 text-sm font-medium text-amber-900 shadow-sm ring-1 ring-amber-300 hover:bg-amber-100 focus:outline-none focus:ring-2 focus:ring-amber-500"
      >
        Back to dashboard
      </Link>
    </div>
  );
}

export default NotAuthorized;
