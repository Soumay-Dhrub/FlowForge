"use client";

/**
 * Chrome shared by every authenticated page: sidebar navigation, the notification bell and the
 * profile menu.
 *
 * Mounted by the `(app)` route group's layout, so a page joins the shell by living in that group —
 * no per-page wrapper to forget, and the public pages (`/login`, `/forgot-password`,
 * `/reset-password`) sit outside the group and get no shell at all.
 */
import { useAuth } from "@/context/AuthContext";
import NotificationBell from "@/components/layout/NotificationBell";
import SidebarNav from "@/components/layout/SidebarNav";
import UserAvatar from "@/components/layout/UserAvatar";

export function AppShell({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();

  return (
    <div className="flex min-h-screen bg-gray-50">
      {/* Collapses off-canvas below `md`; the header keeps the bell and profile menu reachable. */}
      <aside className="hidden w-56 shrink-0 border-r border-gray-200 bg-white md:block">
        <div className="px-4 py-4">
          <span className="text-lg font-bold text-primary-700">FlowForge</span>
        </div>
        <SidebarNav role={user?.roleName} />
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 items-center justify-between gap-4 border-b border-gray-200 bg-white px-4">
          <span className="text-base font-bold text-primary-700 md:hidden">FlowForge</span>
          <div className="ml-auto flex items-center gap-2">
            <NotificationBell />
            <UserAvatar />
          </div>
        </header>

        <main className="min-w-0 flex-1 px-4 py-6">{children}</main>
      </div>
    </div>
  );
}

export default AppShell;
