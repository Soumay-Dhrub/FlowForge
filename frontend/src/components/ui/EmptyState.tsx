"use client";

/**
 * What a list shows when it has nothing to show.
 *
 * Two different situations were being collapsed into one grey sentence: "you have no tasks", which is
 * good news and needs no action, and "nothing matches these filters", which means the filters are hiding
 * something and offering to clear them is the useful response. Telling them apart is the whole point of
 * this component, so `filtered` is a required decision rather than an optional flag.
 */
import { Inbox, SearchX, type LucideIcon } from "lucide-react";

export function EmptyState({
  filtered,
  title,
  description,
  action,
  icon: Icon,
}: {
  /** True when filters are active, so the copy addresses the filters rather than the data. */
  filtered: boolean;
  title: string;
  description?: React.ReactNode;
  /** Usually "Clear filters" when filtered, or the primary create action when genuinely empty. */
  action?: React.ReactNode;
  icon?: LucideIcon;
}) {
  const Fallback = filtered ? SearchX : Inbox;
  const Glyph = Icon ?? Fallback;

  return (
    <div className="flex flex-col items-center justify-center px-6 py-14 text-center">
      <span className="flex h-11 w-11 items-center justify-center rounded-full bg-gray-100 text-gray-400">
        <Glyph aria-hidden className="h-5 w-5" />
      </span>
      <p className="mt-3 text-sm font-medium text-gray-900">{title}</p>
      {description ? (
        <p className="mt-1 max-w-sm text-sm text-gray-500">{description}</p>
      ) : null}
      {action ? <div className="mt-4">{action}</div> : null}
    </div>
  );
}

export default EmptyState;
