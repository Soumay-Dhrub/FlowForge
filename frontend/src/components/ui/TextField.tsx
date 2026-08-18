"use client";

import { forwardRef } from "react";

type TextFieldProps = React.InputHTMLAttributes<HTMLInputElement> & {
  id: string;
  label: string;
  /** Validation message for this field; wired to the input via `aria-describedby`. */
  error?: string;
  hint?: string;
};

/**
 * Labelled text input with its error message programmatically associated to the field.
 *
 * The invalid state is a red border *and* a message. Border colour alone is both invisible to anyone who
 * cannot separate red from grey and useless for saying what is actually wrong, which is the part the
 * person filling in the form needs.
 *
 * Height matches Button's `md` (36px) so a field and a button on one row line up instead of missing each
 * other by a couple of pixels.
 */
export const TextField = forwardRef<HTMLInputElement, TextFieldProps>(function TextField(
  { id, label, error, hint, className = "", ...inputProps },
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
      <input
        id={id}
        ref={ref}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy || undefined}
        className={`h-9 w-full rounded-md border bg-white px-3 text-sm text-gray-900 shadow-xs transition-colors placeholder:text-gray-400 hover:border-gray-400 disabled:cursor-not-allowed disabled:bg-gray-50 disabled:text-gray-500 ${
          error ? "border-danger-600" : "border-gray-300"
        } ${className}`}
        {...inputProps}
      />
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

export default TextField;
