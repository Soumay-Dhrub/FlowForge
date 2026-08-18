/**
 * `/reports/[workflowId]` — workflow analytics (task 39, Requirements 21.1–21.5). ADMIN and MANAGER.
 *
 * The id comes from the route params rather than from a hook, so the client component below stays
 * trivially testable with a plain prop.
 */
import WorkflowPerformanceReport from "@/components/reports/WorkflowPerformanceReport";

export default function WorkflowReportPage({ params }: { params: { workflowId: string } }) {
  return <WorkflowPerformanceReport workflowId={params.workflowId} />;
}
