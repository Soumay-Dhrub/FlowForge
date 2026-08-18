/** Presentation helpers shared by the data tables. */

/**
 * A timestamp as a short local date and time, or an em dash for an absent one.
 *
 * The backend serialises `Instant` as ISO-8601 UTC; rendering it in the reader's own zone is the
 * point, since "published at" only means something relative to where they are.
 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return "—";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "—";
  }
  return date.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** What a metric with no data behind it looks like. Never a zero. */
export const NO_DATA = "—";

/**
 * A duration in seconds at a readable scale, or {@link NO_DATA} for an absent one.
 *
 * `null` is passed straight through rather than coerced, because the reporting endpoints return null
 * for an average over an empty population on purpose: "no requests were decided" and "requests were
 * decided instantly" are different claims, and only one of them is true.
 */
export function formatDurationSeconds(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || Number.isNaN(seconds)) {
    return NO_DATA;
  }
  if (seconds < 60) {
    // Sub-minute dwell is usually a synthetic or automated step; one decimal keeps it honest
    // without pretending to microsecond precision.
    return `${Math.round(seconds * 10) / 10} s`;
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes} min`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    const remainderMinutes = minutes % 60;
    return remainderMinutes === 0 ? `${hours} h` : `${hours} h ${remainderMinutes} min`;
  }
  const days = Math.floor(hours / 24);
  const remainderHours = hours % 24;
  return remainderHours === 0 ? `${days} d` : `${days} d ${remainderHours} h`;
}

/**
 * A ratio in [0,1] as a percentage, or {@link NO_DATA} for an absent one.
 *
 * Same reasoning as {@link formatDurationSeconds}: a null rejection rate means nothing has been
 * decided, which must not read as "0% rejected".
 */
export function formatRatioAsPercent(ratio: number | null | undefined): string {
  if (ratio === null || ratio === undefined || Number.isNaN(ratio)) {
    return NO_DATA;
  }
  return `${(ratio * 100).toFixed(1)}%`;
}

/**
 * An audit action code as prose: `APPROVE_TASK` → `Approve task`.
 *
 * Derived rather than looked up in a table. The set of actions grows with every audited service
 * method, and a lookup that silently rendered an unmapped code as blank would hide exactly the
 * novel activity an auditor is looking for.
 */
export function formatAuditAction(action: string): string {
  const words = action.trim().replace(/_/g, " ").toLowerCase();
  if (!words) {
    return NO_DATA;
  }
  return words.charAt(0).toUpperCase() + words.slice(1);
}
