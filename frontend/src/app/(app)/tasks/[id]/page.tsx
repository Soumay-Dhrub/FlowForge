/**
 * `/tasks/[id]` — task detail and the approve/reject form (task 37, Requirements 13.1, 13.2).
 *
 * The id comes from the route params rather than from a hook so the client component below stays
 * trivially testable with a plain prop.
 */
import TaskDetail from "@/components/tasks/TaskDetail";

export default function TaskDetailPage({ params }: { params: { id: string } }) {
  return <TaskDetail taskId={params.id} />;
}
