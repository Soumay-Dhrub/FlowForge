"use client";

/**
 * A status pill.
 *
 * Task status, instance status and decision were each being coloured by a hand-written map in whichever
 * component happened to need them, so the same PENDING appeared amber in one table and grey in another.
 * The mapping lives here now, and the tone names describe meaning rather than hue.
 *
 * The label is always text. Colour alone would exclude anyone who cannot separate the hues — and would
 * also fail in a screenshot pasted into a ticket, which is how most of these get discussed.
 */
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

/**
 * How a task status reads.
 *
 * ESCALATED is warning rather than danger: a task that missed its deadline needs attention, but nothing
 * has failed — it has been handed to somebody else, which is the safety net working. CANCELLED is
 * neutral because it is an ending, not a problem.
 */
export const TASK_STATUS_TONES: Record<string, BadgeTone> = {
  PENDING: "info",
  ESCALATED: "warning",
  DELEGATED: "accent",
  COMPLETED: "success",
  CANCELLED: "neutral",
};

/**
 * How an instance status reads.
 *
 * REJECTED is warning, not danger: a rejected request is a legitimate outcome of a working process. ERROR
 * is the danger case, because it means the engine could not route the request at all and somebody has to
 * look at the definition.
 */
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
