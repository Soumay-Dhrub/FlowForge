"use client";

/**
 * Chrome shared by every authenticated page: sidebar navigation, the notification bell and the profile
 * menu.
 *
 * Mounted by the `(app)` route group's layout, so a page joins the shell by living in that group — no
 * per-page wrapper to forget, and the public pages sit outside the group and get no shell at all.
 *
 * Two things this fixes beyond appearance. The sidebar used to simply vanish below `md`, leaving no way
 * to navigate on a phone at all; there is now a drawer. And the header is sticky, because these pages
 * are long tables and scrolling to the bottom of one used to mean scrolling back up to reach anything.
 */
import { useEffect, useState } from "react";
import { usePathname } from "next/navigation";
import { Menu, Workflow, X } from "lucide-react";
import { useAuth } from "@/context/AuthContext";
import NotificationBell from "@/components/layout/NotificationBell";
import SidebarNav from "@/components/layout/SidebarNav";
import UserAvatar from "@/components/layout/UserAvatar";

export function AppShell({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  const pathname = usePathname();
  const [drawerOpen, setDrawerOpen] = useState(false);

  // Navigating is the implicit "done" for the drawer. Leaving it open over the new page would cover the
  // thing the user just asked to see.
  useEffect(() => {
    setDrawerOpen(false);
  }, [pathname]);

  // Escape closes it, which is what anyone who has met a dialog will try first.
  useEffect(() => {
    if (!drawerOpen) {
      return;
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setDrawerOpen(false);
      }
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [drawerOpen]);

  return (
    <div className="flex min-h-screen bg-gray-50">
      {/* ── Sidebar, from `md` up ── */}
      <aside className="hidden w-60 shrink-0 border-r border-gray-200 bg-white md:flex md:flex-col">
        <Brand />
        <SidebarNav role={user?.roleName} />
        <RoleFooter role={user?.roleName} />
      </aside>

      {/* ── Drawer, below `md` ── */}
      {drawerOpen ? (
        <div className="fixed inset-0 z-40 md:hidden">
          <div
            className="absolute inset-0 animate-fade-in bg-gray-900/40"
            onClick={() => setDrawerOpen(false)}
            aria-hidden
          />
          <div
            role="dialog"
            aria-modal="true"
            aria-label="Navigation"
            className="absolute inset-y-0 left-0 flex w-64 animate-scale-in flex-col bg-white shadow-popover"
          >
            <div className="flex items-center justify-between border-b border-gray-200 pr-2">
              <Brand />
              <button
                type="button"
                onClick={() => setDrawerOpen(false)}
                aria-label="Close navigation"
                className="rounded-md p-2 text-gray-500 hover:bg-gray-100 hover:text-gray-900"
              >
                <X aria-hidden className="h-5 w-5" />
              </button>
            </div>
            <SidebarNav role={user?.roleName} />
            <RoleFooter role={user?.roleName} />
          </div>
        </div>
      ) : null}

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 flex h-14 items-center gap-3 border-b border-gray-200 bg-white/85 px-4 backdrop-blur supports-[backdrop-filter]:bg-white/70">
          <button
            type="button"
            onClick={() => setDrawerOpen(true)}
            aria-label="Open navigation"
            aria-expanded={drawerOpen}
            className="-ml-1 rounded-md p-2 text-gray-600 hover:bg-gray-100 hover:text-gray-900 md:hidden"
          >
            <Menu aria-hidden className="h-5 w-5" />
          </button>

          <span className="text-sm font-semibold text-gray-900 md:hidden">FlowForge</span>

          <div className="ml-auto flex items-center gap-1.5">
            <NotificationBell />
            <span aria-hidden className="mx-1 hidden h-6 w-px bg-gray-200 sm:block" />
            <UserAvatar />
          </div>
        </header>

        {/* Target of the skip link in the root layout. `tabIndex={-1}` so focus can actually land here. */}
        <main
          id="main-content"
          tabIndex={-1}
          className="min-w-0 flex-1 px-4 py-6 focus:outline-none sm:px-6 lg:px-8"
        >
          {/* Capped and centred: full-bleed tables on a wide monitor force the eye across 2000px to
              connect a row's first cell to its last. */}
          <div className="mx-auto w-full max-w-7xl">{children}</div>
        </main>
      </div>
    </div>
  );
}

/** The wordmark. A mark plus the name, so the product is identifiable when the sidebar is all a user sees. */
function Brand() {
  return (
    <div className="flex h-14 items-center gap-2.5 px-4">
      <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-primary-600 text-white shadow-xs">
        <Workflow aria-hidden className="h-4 w-4" />
      </span>
      <span className="text-base font-semibold tracking-tight text-gray-900">FlowForge</span>
    </div>
  );
}

/**
 * The caller's role, pinned to the bottom of the sidebar.
 *
 * What you can see in this product depends entirely on your role, so "why can I not find Users?" is a
 * predictable question. Showing the role answers it without a support conversation.
 */
function RoleFooter({ role }: { role: string | undefined }) {
  if (!role) {
    return null;
  }
  return (
    <div className="mt-auto border-t border-gray-200 px-4 py-3">
      <p className="text-xs text-gray-500">
        Signed in as <span className="font-medium text-gray-700">{role}</span>
      </p>
    </div>
  );
}

export default AppShell;
