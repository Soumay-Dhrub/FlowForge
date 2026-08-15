"use client";

/**
 * Modal dialog.
 *
 * Unlike {@link ../../lib/usePopover usePopover}, this *does* trap focus, and deliberately so: a
 * dialog makes the page behind it unavailable, so letting Tab wander into content the user cannot
 * act on would strand keyboard and screen-reader users outside the only thing they can interact
 * with. Escape closes, and focus returns to whatever opened the dialog so the user lands back where
 * they were rather than at the top of the document.
 */
import { useCallback, useEffect, useRef } from "react";
import { X } from "lucide-react";

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

interface ModalProps {
  /** Rendered only while true; mounting is what starts the focus trap. */
  open: boolean;
  onClose: () => void;
  title: string;
  /** Optional supporting copy, announced with the title via `aria-describedby`. */
  description?: string;
  children: React.ReactNode;
}

export function Modal({ open, onClose, title, description, children }: ModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null);
  const titleId = "modal-title";
  const descriptionId = "modal-description";

  const focusable = useCallback((): HTMLElement[] => {
    const dialog = dialogRef.current;
    if (!dialog) {
      return [];
    }
    return Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE));
  }, []);

  // Move focus in on open and back to the trigger on close.
  useEffect(() => {
    if (!open) {
      return;
    }
    const previouslyFocused = document.activeElement as HTMLElement | null;
    const [first] = focusable();
    (first ?? dialogRef.current)?.focus();

    return () => {
      previouslyFocused?.focus();
    };
  }, [open, focusable]);

  useEffect(() => {
    if (!open) {
      return;
    }

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.stopPropagation();
        onClose();
        return;
      }
      if (event.key !== "Tab") {
        return;
      }

      const elements = focusable();
      if (elements.length === 0) {
        event.preventDefault();
        return;
      }
      const first = elements[0];
      const last = elements[elements.length - 1];
      const active = document.activeElement;

      // Wrap at both ends; also pulls focus back in if it has escaped the dialog entirely.
      if (event.shiftKey && (active === first || !dialogRef.current?.contains(active))) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (active === last || !dialogRef.current?.contains(active))) {
        event.preventDefault();
        first.focus();
      }
    };

    document.addEventListener("keydown", onKeyDown, true);
    return () => document.removeEventListener("keydown", onKeyDown, true);
  }, [open, onClose, focusable]);

  if (!open) {
    return null;
  }

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-gray-900/40 p-4 pt-16">
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descriptionId : undefined}
        tabIndex={-1}
        className="w-full max-w-md rounded-lg bg-white p-5 shadow-xl outline-none"
      >
        <div className="flex items-start justify-between gap-4">
          <div>
            <h2 id={titleId} className="text-lg font-semibold text-gray-900">
              {title}
            </h2>
            {description ? (
              <p id={descriptionId} className="mt-1 text-sm text-gray-600">
                {description}
              </p>
            ) : null}
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-md p-1 text-gray-500 hover:bg-gray-100 hover:text-gray-700 focus:outline-none focus:ring-2 focus:ring-primary-500"
          >
            <X aria-hidden="true" className="h-4 w-4" />
            <span className="sr-only">Close</span>
          </button>
        </div>
        <div className="mt-4">{children}</div>
      </div>
    </div>
  );
}

export default Modal;
