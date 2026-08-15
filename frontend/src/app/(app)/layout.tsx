/**
 * Layout for every authenticated page.
 *
 * A route group (`(app)`) rather than a path segment: the parentheses keep it out of the URL, so
 * `/dashboard` stays `/dashboard` while gaining the guard and the shell. Pages that must not have
 * the sidebar — `/login`, `/forgot-password`, `/reset-password` — simply live outside the group,
 * which is what makes "authenticated pages only" a structural fact rather than a per-page habit.
 */
import AppShell from "@/components/layout/AppShell";
import ProtectedRoute from "@/components/auth/ProtectedRoute";

export default function AuthenticatedLayout({ children }: { children: React.ReactNode }) {
  return (
    <ProtectedRoute>
      <AppShell>{children}</AppShell>
    </ProtectedRoute>
  );
}
