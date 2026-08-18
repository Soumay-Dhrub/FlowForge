"use client";

import { forwardRef } from "react";
import { ChevronDown } from "lucide-react";

type SelectFieldProps = React.SelectHTMLAttributes<HTMLSelectElement> & {
  id: string;
  label: string;
  /** Validation message for this field; wired to the control via `aria-describedby`. */
  error?: string;
  hint?: string;
};

/**
 * Labelled select with its error message programmatically associated to the control.
 *
 * The native chevron is suppressed and redrawn, because the platform default differs enough between
 * macOS, Windows and Linux that a filter bar looks assembled from parts. The element underneath is still
 * a real `<select>` — keyboard behaviour, type-ahead and the mobile picker are all worth more than a
 * custom listbox that reimplements them badly.
 */
export const SelectField = forwardRef<HTMLSelectElement, SelectFieldProps>(function SelectField(
  { id, label, error, hint, className = "", children, ...selectProps },
  ref,
) {
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(" ");

  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="block text-sm font-medium text-gray-700">
        {label}
      </label>
      <div className="relative">
        <select
          id={id}
          ref={ref}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy || undefined}
          className={`h-9 w-full appearance-none rounded-md border bg-white pl-3 pr-9 text-sm text-gray-900 shadow-xs transition-colors hover:border-gray-400 disabled:cursor-not-allowed disabled:bg-gray-50 disabled:text-gray-500 ${
            error ? "border-danger-600" : "border-gray-300"
          } ${className}`}
          {...selectProps}
        >
          {children}
        </select>
        <ChevronDown
          aria-hidden
          className="pointer-events-none absolute right-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400"
        />
      </div>
      {hint ? (
        <p id={hintId} className="text-xs text-gray-500">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} role="alert" className="text-sm text-danger-700">
          {error}
        </p>
      ) : null}
    </div>
  );
});

export default SelectField;
