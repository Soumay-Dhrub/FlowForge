/**
 * `/workflows/[id]` — workflow detail and version history (task 36, Requirement 8.3).
 *
 * The id comes from the route params here rather than from a hook so the client component below
 * stays trivially testable with a plain prop.
 */
import WorkflowVersionHistory from "@/components/workflows/WorkflowVersionHistory";

export default function WorkflowDetailPage({ params }: { params: { id: string } }) {
  return <WorkflowVersionHistory workflowId={params.id} />;
}
