"use client";

/**
 * The top of a page: what this screen is, one line on what it is for, and its primary action.
 *
 * Consistency here does more for perceived quality than any amount of styling. Every page previously
 * chose its own heading size and its own gap before the content, so moving between them felt like
 * moving between products.
 *
 * The description is not decoration. Several of these screens are ambiguous on their name alone —
 * "Tasks" could mean tasks I own or every task in the system — and a sentence answers that far more
 * cheaply than a support conversation.
 */
export function PageHeader({
  title,
  description,
  actions,
  breadcrumb,
}: {
  title: React.ReactNode;
  description?: React.ReactNode;
  actions?: React.ReactNode;
  /** A back link for pages reached from a list, rendered above the title. */
  breadcrumb?: React.ReactNode;
}) {
  return (
    <header className="mb-6">
      {breadcrumb ? <div className="mb-2 text-sm">{breadcrumb}</div> : null}
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-3">
        <div className="min-w-0">
          <h1 className="text-2xl font-semibold text-gray-900">{title}</h1>
          {description ? (
            <p className="mt-1 max-w-2xl text-sm text-gray-500">{description}</p>
          ) : null}
        </div>
        {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
      </div>
    </header>
  );
}

export default PageHeader;
