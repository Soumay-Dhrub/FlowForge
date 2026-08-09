"use client";

import { Loader2 } from "lucide-react";

type SubmitButtonProps = {
  isSubmitting: boolean;
  /** Label shown while the request is in flight. */
  pendingLabel: string;
  children: React.ReactNode;
};

/** Submit button with a visible, announced busy state and a disabled appearance to match. */
export function SubmitButton({ isSubmitting, pendingLabel, children }: SubmitButtonProps) {
  return (
    <button
      type="submit"
      disabled={isSubmitting}
      aria-busy={isSubmitting}
      className="flex w-full items-center justify-center gap-2 rounded-md bg-primary-600 px-4 py-2 font-medium text-white transition hover:bg-primary-700 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:bg-primary-500 disabled:opacity-60"
    >
      {isSubmitting ? <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" /> : null}
      {isSubmitting ? pendingLabel : children}
    </button>
  );
}

export default SubmitButton;
