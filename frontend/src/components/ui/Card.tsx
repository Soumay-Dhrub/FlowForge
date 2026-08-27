"use client";

import type { LucideIcon } from "lucide-react";

export function Card({
  as: Tag = "div",
  padded = true,
  className = "",
  children,
  ...rest
}: {
  as?: React.ElementType;
  /** Off when the card holds a table or list that manages its own edge-to-edge padding. */
  padded?: boolean;
  className?: string;
  children: React.ReactNode;
} & React.HTMLAttributes<HTMLElement>) {
  return (
    <Tag
      className={`rounded-xl border border-gray-200 bg-white shadow-xs ${padded ? "p-5" : ""} ${className}`}
      {...rest}
    >
      {children}
    </Tag>
  );
}

/** A card's own heading row, with optional actions on the right. */
export function CardHeader({
  title,
  description,
  actions,
  className = "",
}: {
  title: React.ReactNode;
  description?: React.ReactNode;
  actions?: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={`flex flex-wrap items-start justify-between gap-3 ${className}`}>
      <div className="min-w-0">
        <h2 className="text-sm font-semibold text-gray-900">{title}</h2>
        {description ? <p className="mt-0.5 text-sm text-gray-500">{description}</p> : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
    </div>
  );
}

export function StatCard({
  label,
  value,
  hint,
  icon: Icon,
  tone = "neutral",
}: {
  label: string;
  value: React.ReactNode;
  hint?: React.ReactNode;
  icon?: LucideIcon;
  tone?: "neutral" | "accent";
}) {
  return (
    <Card className="flex items-start gap-4">
      {Icon ? (
        <span
          className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${
            tone === "accent" ? "bg-primary-50 text-primary-600" : "bg-gray-100 text-gray-500"
          }`}
        >
          <Icon aria-hidden className="h-4.5 w-4.5" />
        </span>
      ) : null}
      <div className="min-w-0">
        <p className="text-xs font-medium uppercase tracking-wide text-gray-500">{label}</p>
        <p className="mt-1 text-2xl font-semibold text-gray-900">{value}</p>
        {hint ? <p className="mt-0.5 text-xs text-gray-500">{hint}</p> : null}
      </div>
    </Card>
  );
}

export default Card;
