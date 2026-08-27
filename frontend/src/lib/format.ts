/** Presentation helpers shared by the data tables. */

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

export function formatRatioAsPercent(ratio: number | null | undefined): string {
  if (ratio === null || ratio === undefined || Number.isNaN(ratio)) {
    return NO_DATA;
  }
  return `${(ratio * 100).toFixed(1)}%`;
}

export function formatAuditAction(action: string): string {
  const words = action.trim().replace(/_/g, " ").toLowerCase();
  if (!words) {
    return NO_DATA;
  }
  return words.charAt(0).toUpperCase() + words.slice(1);
}
