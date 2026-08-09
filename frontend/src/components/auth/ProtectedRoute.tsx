"use client";

/**
 * Gate for authenticated pages. Unauthenticated visitors are sent to `/login`; nothing protected
 * is rendered while the session is still being restored.
 *
 * This is a client-side guard, not a security boundary — the API rejects unauthenticated calls
 * with 401 regardless. Next.js middleware cannot be used here because the tokens live in
 * `localStorage`, which the edge runtime cannot read.
 */
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { useAuth } from "@/context/AuthContext";

export function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { status } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (status === "unauthenticated") {
      router.replace("/login");
    }
  }, [status, router]);

  if (status !== "authenticated") {
    return (
      <div className="flex min-h-screen items-center justify-center" role="status" aria-live="polite">
        <span className="text-sm text-gray-600">
          {status === "loading" ? "Loading your session…" : "Redirecting to sign in…"}
        </span>
      </div>
    );
  }

  return <>{children}</>;
}

export default ProtectedRoute;
