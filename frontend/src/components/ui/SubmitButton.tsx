"use client";

import { Loader2 } from "lucide-react";

type SubmitButtonProps = {
  isSubmitting: boolean;
  /** Label shown while the request is in flight. */
  pendingLabel: string;
  children: React.ReactNode;
  /** Off for inline forms, where a full-width button would look like a page-level action. */
  fullWidth?: boolean;
};

export function SubmitButton({
  isSubmitting,
  pendingLabel,
  children,
  fullWidth = true,
}: SubmitButtonProps) {
  return (
    <button
      type="submit"
      disabled={isSubmitting}
      aria-busy={isSubmitting}
      className={`inline-flex h-9 items-center justify-center gap-2 rounded-md bg-primary-600 px-4 text-sm font-medium text-white shadow-xs transition-colors hover:bg-primary-700 active:bg-primary-800 disabled:cursor-not-allowed disabled:bg-primary-300 ${
        fullWidth ? "w-full" : ""
      }`}
    >
      {isSubmitting ? <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" /> : null}
      {isSubmitting ? pendingLabel : children}
    </button>
  );
}

export default SubmitButton;
