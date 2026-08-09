import type { Metadata } from "next";
import AuthCard from "@/components/auth/AuthCard";
import LoginForm from "@/components/auth/LoginForm";

export const metadata: Metadata = {
  title: "Sign in · FlowForge",
};

export default function LoginPage() {
  return (
    <AuthCard title="Sign in" description="Use your FlowForge account to continue.">
      <LoginForm />
    </AuthCard>
  );
}
