/**
 * `/tasks` — the task queue (task 37, Requirements 12.1, 12.2, 12.3).
 *
 * Inside the `(app)` route group, so `ProtectedRoute` and the app shell already apply. `GET /api/tasks`
 * is open to any authenticated caller and scoped to their own tasks, so every role gets a usable page.
 */
import TaskList from "@/components/tasks/TaskList";

export default function TasksPage() {
  return <TaskList />;
}
