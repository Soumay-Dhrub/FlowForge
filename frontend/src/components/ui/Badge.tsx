"use client";

export type BadgeTone = "neutral" | "info" | "success" | "warning" | "danger" | "accent";

const TONES: Record<BadgeTone, string> = {
  neutral: "bg-gray-100 text-gray-700 ring-gray-200",
  info: "bg-info-50 text-info-800 ring-info-200",
  success: "bg-success-50 text-success-800 ring-success-200",
  warning: "bg-warning-50 text-warning-800 ring-warning-200",
  danger: "bg-danger-50 text-danger-800 ring-danger-200",
  accent: "bg-primary-50 text-primary-700 ring-primary-200",
};

export function Badge({
  tone = "neutral",
  children,
  className = "",
}: {
  tone?: BadgeTone;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${TONES[tone]} ${className}`}
    >
      {children}
    </span>
  );
}

export const TASK_STATUS_TONES: Record<string, BadgeTone> = {
  PENDING: "info",
  ESCALATED: "warning",
  DELEGATED: "accent",
  COMPLETED: "success",
  CANCELLED: "neutral",
};

export const INSTANCE_STATUS_TONES: Record<string, BadgeTone> = {
  RUNNING: "info",
  COMPLETED: "success",
  REJECTED: "warning",
  ERROR: "danger",
  CANCELLED: "neutral",
};

/** A recorded decision. */
export const DECISION_TONES: Record<string, BadgeTone> = {
  APPROVED: "success",
  REJECTED: "warning",
};

export default Badge;
