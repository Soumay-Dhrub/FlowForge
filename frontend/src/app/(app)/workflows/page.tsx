/**
 * `/workflows` — the workflow list (task 36).
 *
 * Inside the `(app)` route group, so `ProtectedRoute` and the app shell already apply. The backend
 * restricts `/api/workflows` to ADMIN and MANAGER; an EMPLOYEE who reaches this URL gets the table's
 * error state from the 403 rather than a broken page.
 */
import WorkflowList from "@/components/workflows/WorkflowList";

export default function WorkflowsPage() {
  return <WorkflowList />;
}
