/**
 * `/dashboard` — the personal dashboard (task 39, Requirements 20.1, 20.2, 20.3).
 *
 * Inside the `(app)` route group, so `ProtectedRoute` and the app shell already apply, and it is the
 * redirect target after sign-in. `GET /api/reports/dashboard` is scoped to the caller by the token
 * alone, so every role gets a usable page.
 */
import DashboardOverview from "@/components/reports/DashboardOverview";

export default function DashboardPage() {
  return <DashboardOverview />;
}
