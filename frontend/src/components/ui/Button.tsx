"use client";

import { forwardRef } from "react";
import { Loader2, type LucideIcon } from "lucide-react";

type Variant = "primary" | "secondary" | "ghost" | "danger";
type Size = "sm" | "md";

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  /** Shows a spinner, keeps focus, and swallows clicks. Prefer this to `disabled` for in-flight work. */
  loading?: boolean;
  /** Announced while `loading`, so the change is not visual only. */
  loadingLabel?: string;
  icon?: LucideIcon;
}

const VARIANTS: Record<Variant, string> = {
  primary:
    "bg-primary-600 text-white shadow-xs hover:bg-primary-700 active:bg-primary-800 disabled:bg-primary-300",
  secondary:
    "border border-gray-300 bg-white text-gray-700 shadow-xs hover:bg-gray-50 hover:text-gray-900 active:bg-gray-100 disabled:text-gray-400",
  ghost: "text-gray-600 hover:bg-gray-100 hover:text-gray-900 active:bg-gray-200 disabled:text-gray-400",
  danger:
    "bg-danger-600 text-white shadow-xs hover:bg-danger-700 active:bg-danger-800 disabled:bg-danger-200",
};

const SIZES: Record<Size, string> = {
  // 32px and 36px tall. Both clear the 24px minimum target size, and the icon-only case pads to square.
  sm: "h-8 gap-1.5 px-3 text-xs",
  md: "h-9 gap-2 px-3.5 text-sm",
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = "secondary",
    size = "md",
    loading = false,
    loadingLabel,
    icon: Icon,
    className = "",
    children,
    type = "button",
    disabled,
    onClick,
    ...rest
  },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      disabled={disabled}
      aria-busy={loading || undefined}
      onClick={loading ? undefined : onClick}
      className={`inline-flex items-center justify-center whitespace-nowrap rounded-md font-medium transition-colors disabled:cursor-not-allowed ${SIZES[size]} ${VARIANTS[variant]} ${className}`}
      {...rest}
    >
      {loading ? (
        <Loader2 aria-hidden className="h-4 w-4 shrink-0 animate-spin" />
      ) : Icon ? (
        <Icon aria-hidden className="h-4 w-4 shrink-0" />
      ) : null}
      {loading && loadingLabel ? loadingLabel : children}
    </button>
  );
});

export default Button;
