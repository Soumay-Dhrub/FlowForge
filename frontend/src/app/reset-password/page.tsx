import type { Metadata } from "next";
import { Suspense } from "react";
import AuthCard from "@/components/auth/AuthCard";
import ResetPasswordForm from "@/components/auth/ResetPasswordForm";

export const metadata: Metadata = {
  title: "Reset password · FlowForge",
};

export default function ResetPasswordPage() {
  return (
    <AuthCard title="Choose a new password" description="This reset link can be used once.">
      {/* useSearchParams needs a Suspense boundary to keep the page statically renderable. */}
      <Suspense fallback={<p className="text-sm text-gray-600">Loading…</p>}>
        <ResetPasswordForm />
      </Suspense>
    </AuthCard>
  );
}
