"use client";

/** A single grey bar. Width is a class so callers can vary it and avoid a suspiciously even grid. */
export function SkeletonBar({ className = "h-4 w-full" }: { className?: string }) {
  return <span className={`skeleton block ${className}`} />;
}

export function SkeletonTableRows({
  rows = 5,
  columns,
  label,
}: {
  rows?: number;
  columns: number;
  label: string;
}) {
  // Varied widths, so the placeholder reads as text rather than as a grid of identical blocks.
  const widths = ["w-3/4", "w-1/2", "w-2/3", "w-5/6", "w-1/3"];

  return (
    <>
      {Array.from({ length: rows }).map((_, rowIndex) => (
        <tr key={rowIndex} aria-hidden={rowIndex > 0 ? true : undefined}>
          {Array.from({ length: columns }).map((__, columnIndex) => (
            <td key={columnIndex} className="px-4 py-3.5">
              {rowIndex === 0 && columnIndex === 0 ? (
                <span role="status" className="block">
                  <span className="sr-only">{label}</span>
                  <SkeletonBar className={`h-4 ${widths[columnIndex % widths.length]}`} />
                </span>
              ) : (
                <SkeletonBar className={`h-4 ${widths[(rowIndex + columnIndex) % widths.length]}`} />
              )}
            </td>
          ))}
        </tr>
      ))}
    </>
  );
}

/** Placeholder for a card-shaped region. */
export function SkeletonCard({ label, lines = 3 }: { label: string; lines?: number }) {
  return (
    <div role="status" className="rounded-xl border border-gray-200 bg-white p-5 shadow-xs">
      <span className="sr-only">{label}</span>
      <SkeletonBar className="h-4 w-1/3" />
      <div className="mt-4 space-y-2.5">
        {Array.from({ length: lines }).map((_, index) => (
          <SkeletonBar key={index} className={`h-3.5 ${index === lines - 1 ? "w-2/3" : "w-full"}`} />
        ))}
      </div>
    </div>
  );
}

export default SkeletonBar;
