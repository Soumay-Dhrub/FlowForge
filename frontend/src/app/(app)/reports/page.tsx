/**
 * `/reports` — pick a workflow to report on (task 39). ADMIN and MANAGER only.
 *
 * The sidebar hides the link for an EMPLOYEE, but that is presentation: the page itself refuses with
 * a "not authorized" panel rather than firing a request that is answered 403.
 */
import ReportsIndex from "@/components/reports/ReportsIndex";

export default function ReportsPage() {
  return <ReportsIndex />;
}
