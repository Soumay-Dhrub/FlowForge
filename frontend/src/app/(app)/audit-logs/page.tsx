/**
 * `/audit-logs` — the audit trail viewer (task 40, Requirements 19.3, 19.4). ADMIN only.
 *
 * The sidebar hides the link for everyone else, but that is presentation: a MANAGER can still type
 * the URL, so the page refuses deliberately with a "not authorized" panel rather than firing a
 * request that is answered 403. This is the sharpest read surface in the system — an entry's
 * before/after state can contain any field of any entity.
 */
import AuditLogTable from "@/components/audit/AuditLogTable";

export default function AuditLogsPage() {
  return <AuditLogTable />;
}
