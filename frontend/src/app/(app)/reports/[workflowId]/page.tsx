import WorkflowPerformanceReport from "@/components/reports/WorkflowPerformanceReport";

export default function WorkflowReportPage({ params }: { params: { workflowId: string } }) {
  return <WorkflowPerformanceReport workflowId={params.workflowId} />;
}
