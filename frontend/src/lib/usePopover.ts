"use client";

/**
 * Open/closed state for a disclosure popup (the notification bell, the profile menu) together with
 * the keyboard and pointer behaviour a popup owes its users.
 *
 * Deliberately *not* a focus trap. These are menus attached to a button, not modal dialogs: the
 * page behind them stays usable, so trapping focus would strand keyboard and screen-reader users
 * inside a panel they did not ask to be confined to. Escape closes and moves focus back to the
 * trigger, which is what returns them to where they were.
 */
import { useCallback, useEffect, useRef, useState } from "react";

export interface Popover<T extends HTMLElement = HTMLDivElement> {
  open: boolean;
  toggle: () => void;
  close: () => void;
  /** Wrap the trigger and the panel together; a click outside this element closes the popup. */
  containerRef: React.RefObject<T>;
  /** Attach to the trigger so Escape can return focus to it. */
  triggerRef: React.RefObject<HTMLButtonElement>;
}

export function usePopover<T extends HTMLElement = HTMLDivElement>(): Popover<T> {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<T>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);

  const close = useCallback(() => setOpen(false), []);
  const toggle = useCallback(() => setOpen((wasOpen) => !wasOpen), []);

  useEffect(() => {
    if (!open) {
      return;
    }

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
        // Focus would otherwise be left on a node that has just been removed from the document.
        triggerRef.current?.focus();
      }
    };

    const onPointerDown = (event: MouseEvent | TouchEvent) => {
      const container = containerRef.current;
      if (container && !container.contains(event.target as Node)) {
        setOpen(false);
      }
    };

    document.addEventListener("keydown", onKeyDown);
    document.addEventListener("mousedown", onPointerDown);
    document.addEventListener("touchstart", onPointerDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("mousedown", onPointerDown);
      document.removeEventListener("touchstart", onPointerDown);
    };
  }, [open]);

  return { open, toggle, close, containerRef, triggerRef };
}

export default usePopover;
