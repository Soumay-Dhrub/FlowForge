/** Typed wrappers for the `/api/notifications` endpoints. */
import api, { unwrap } from "@/lib/api";
import type { ApiResponse, Notification } from "@/types";

/** Query keys, shared so the bell's optimistic update touches the same caches the polls fill. */
export const notificationKeys = {
  list: ["notifications", "list"] as const,
  unreadCount: ["notifications", "unread-count"] as const,
};

/** `GET /api/notifications` — the caller's own notifications, unread first then newest. */
export async function fetchNotifications(): Promise<Notification[]> {
  return unwrap(await api.get<ApiResponse<Notification[]>>("/notifications"));
}

/**
 * `GET /api/notifications/unread-count` — just the badge number.
 *
 * Separate from the list because the bell polls this on a timer and only needs an integer.
 */
export async function fetchUnreadCount(): Promise<number> {
  return unwrap(await api.get<ApiResponse<number>>("/notifications/unread-count"));
}

/**
 * `PATCH /api/notifications/{id}/read` — 403 for someone else's notification, 404 for an unknown
 * id, 200 (and no change) for one that was already read.
 */
export async function markNotificationRead(id: string): Promise<Notification> {
  return unwrap(await api.patch<ApiResponse<Notification>>(`/notifications/${id}/read`));
}

/** Human-readable labels for the event types the platform raises itself. */
export const NOTIFICATION_EVENT_LABELS: Record<string, string> = {
  TASK_ASSIGNED: "Task assigned",
  TASK_APPROVED: "Task approved",
  TASK_REJECTED: "Task rejected",
  TASK_ESCALATED: "Task escalated",
  WORKFLOW_NOTIFICATION: "Workflow notification",
};

/** Title for a notification row: the mapped label, or the raw event type for a custom one. */
export function notificationTitle(notification: Notification): string {
  return (
    NOTIFICATION_EVENT_LABELS[notification.eventType] ??
    notification.eventType.replace(/_/g, " ").toLowerCase()
  );
}

/** The emitter's `message`, when it supplied one. */
export function notificationMessage(notification: Notification): string | null {
  const message = notification.payload?.message;
  return typeof message === "string" && message.trim() ? message : null;
}
