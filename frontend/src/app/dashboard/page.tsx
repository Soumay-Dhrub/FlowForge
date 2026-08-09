"use client";

/**
 * Deliberately plain placeholder so `/login` has a real redirect target and logout can be
 * exercised end to end. The real dashboard (widgets, activity feed) is task 39.
 */
import { useRouter } from "next/navigation";
import { useState } from "react";
import ProtectedRoute from "@/components/auth/ProtectedRoute";
import { useAuth } from "@/context/AuthContext";

function DashboardContent() {
  const { user, logout } = useAuth();
  const router = useRouter();
  const [signingOut, setSigningOut] = useState(false);

  const onLogout = async () => {
    setSigningOut(true);
    await logout();
    router.replace("/login");
  };

  return (
    <main className="mx-auto max-w-2xl px-4 py-12">
      <h1 className="text-2xl font-bold text-primary-700">Dashboard</h1>
      <p className="mt-1 text-sm text-gray-600">Signed in to FlowForge.</p>

      <dl className="mt-8 divide-y divide-gray-200 rounded-lg border border-gray-200">
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

      <button
        type="button"
        onClick={onLogout}
        disabled={signingOut}
        aria-busy={signingOut}
        className="mt-8 rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60"
      >
        {signingOut ? "Signing out…" : "Sign out"}
      </button>
    </main>
  );
}

export default function DashboardPage() {
  return (
    <ProtectedRoute>
      <DashboardContent />
    </ProtectedRoute>
  );
}
