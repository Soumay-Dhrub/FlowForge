/**
 * `/workflows/[id]/edit` — the drag-and-drop workflow builder (task 35, Requirements 6.1–6.5).
 *
 * Inside the `(app)` route group, so `ProtectedRoute` and the app shell already apply. The id is read
 * from the route params here and handed down as a plain prop, keeping the client component testable
 * without a router.
 */
import WorkflowBuilder from "@/components/workflows/WorkflowBuilder";

export default function WorkflowEditPage({ params }: { params: { id: string } }) {
  return <WorkflowBuilder workflowId={params.id} />;
}
