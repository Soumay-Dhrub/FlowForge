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
