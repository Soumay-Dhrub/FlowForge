import WorkflowBuilder from "@/components/workflows/WorkflowBuilder";

export default function WorkflowEditPage({ params }: { params: { id: string } }) {
  return <WorkflowBuilder workflowId={params.id} />;
}
