"use client";

/**
 * Deliberately plain placeholder so `/login` has a real redirect target. The real dashboard
 * (pending tasks, submitted requests, activity feed) is task 39.
 *
 * The guard and the chrome now come from the `(app)` route group's layout, so this page no longer
 * wraps itself in `ProtectedRoute` and no longer carries its own sign-out button — signing out
 * lives in the profile menu, where every authenticated page has it.
 */
import { useAuth } from "@/context/AuthContext";

export default function DashboardPage() {
  const { user } = useAuth();

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="text-2xl font-bold text-primary-700">Dashboard</h1>
      <p className="mt-1 text-sm text-gray-600">Signed in to FlowForge.</p>

      <dl className="mt-8 divide-y divide-gray-200 rounded-lg border border-gray-200 bg-white">
        <div className="flex justify-between px-4 py-3">
          <dt className="text-sm font-medium text-gray-500">Name</dt>
          <dd className="text-sm text-gray-900">{user?.name}</dd>
        </div>
        <div className="flex justify-between px-4 py-3">
          <dt className="text-sm font-medium text-gray-500">Email</dt>
          <dd className="text-sm text-gray-900">{user?.email}</dd>
        </div>
        <div className="flex justify-between px-4 py-3">
          <dt className="text-sm font-medium text-gray-500">Role</dt>
          <dd className="text-sm text-gray-900">{user?.roleName}</dd>
        </div>
      </dl>
    </div>
  );
}
