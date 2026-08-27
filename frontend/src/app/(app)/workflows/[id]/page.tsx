import WorkflowVersionHistory from "@/components/workflows/WorkflowVersionHistory";

export default function WorkflowDetailPage({ params }: { params: { id: string } }) {
  return <WorkflowVersionHistory workflowId={params.id} />;
}
