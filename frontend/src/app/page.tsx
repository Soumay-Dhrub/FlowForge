import Link from "next/link";

export default function Home() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center p-24">
      <h1 className="text-4xl font-bold text-primary-700">FlowForge</h1>
      <p className="mt-4 text-gray-600">Configurable Workflow Orchestration Platform</p>
      <Link
        href="/login"
        className="mt-8 rounded-md bg-primary-600 px-4 py-2 font-medium text-white hover:bg-primary-700 focus:ring-offset-2"
      >
        Sign in
      </Link>
    </main>
  );
}
