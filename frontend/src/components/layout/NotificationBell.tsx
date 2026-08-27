"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Bell, Loader2 } from "lucide-react";
import {
  fetchNotifications,
  fetchUnreadCount,
  markNotificationRead,
  notificationKeys,
  notificationMessage,
  notificationTitle,
} from "@/lib/notificationsApi";
import usePopover from "@/lib/usePopover";
import type { Notification } from "@/types";

const POLL_INTERVAL_MS = 30_000;

/** Badge caps at 99+ so a long-neglected inbox cannot stretch the button. */
function badgeLabel(count: number): string {
  return count > 99 ? "99+" : String(count);
}

function formatTimestamp(iso: string): string {
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? "" : parsed.toLocaleString();
}

export function NotificationBell() {
  const { open, toggle, close, containerRef, triggerRef } = usePopover();
  const queryClient = useQueryClient();

  const unreadCountQuery = useQuery({
    queryKey: notificationKeys.unreadCount,
    queryFn: fetchUnreadCount,
    refetchInterval: POLL_INTERVAL_MS,
  });

  const notificationsQuery = useQuery({
    queryKey: notificationKeys.list,
    queryFn: fetchNotifications,
    enabled: open,
    refetchInterval: open ? POLL_INTERVAL_MS : false,
  });

  const markRead = useMutation({
    mutationFn: markNotificationRead,
    // Optimistic: the click should feel instant, and the only state it changes is one boolean.
    onMutate: async (id: string) => {
      await Promise.all([
        queryClient.cancelQueries({ queryKey: notificationKeys.list }),
        queryClient.cancelQueries({ queryKey: notificationKeys.unreadCount }),
      ]);

      const previousList = queryClient.getQueryData<Notification[]>(notificationKeys.list);
      const previousCount = queryClient.getQueryData<number>(notificationKeys.unreadCount);
      const wasUnread = previousList?.some((item) => item.id === id && !item.isRead) ?? false;

      if (previousList) {
        queryClient.setQueryData<Notification[]>(
          notificationKeys.list,
          previousList.map((item) => (item.id === id ? { ...item, isRead: true } : item)),
        );
      }
      // Only a notification that was actually unread moves the badge, so a second click on the
      // same row cannot drive the count negative.
      if (typeof previousCount === "number" && wasUnread) {
        queryClient.setQueryData<number>(notificationKeys.unreadCount, Math.max(0, previousCount - 1));
      }

      return { previousList, previousCount };
    },
    onError: (_error, _id, context) => {
      // Roll back to exactly what was there: a failed PATCH (403, 404, offline) must not leave the
      // UI claiming the notification was read.
      if (context?.previousList !== undefined) {
        queryClient.setQueryData(notificationKeys.list, context.previousList);
      }
      if (context?.previousCount !== undefined) {
        queryClient.setQueryData(notificationKeys.unreadCount, context.previousCount);
      }
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: notificationKeys.list });
      queryClient.invalidateQueries({ queryKey: notificationKeys.unreadCount });
    },
  });

  const unreadCount = unreadCountQuery.data ?? 0;
  const notifications = notificationsQuery.data ?? [];
  const unreadText =
    unreadCount === 0
      ? "No unread notifications"
      : `${unreadCount} unread notification${unreadCount === 1 ? "" : "s"}`;

  return (
    <div className="relative" ref={containerRef}>
      <button
        ref={triggerRef}
        type="button"
        onClick={toggle}
        aria-expanded={open}
        aria-controls="notification-panel"
        // The badge is a number in a coloured circle; the accessible name carries the same fact.
        aria-label={`Notifications (${unreadText})`}
        className="relative rounded-full p-2 text-gray-600 hover:bg-gray-100 hover:text-gray-900 focus:ring-offset-2"
      >
        <Bell aria-hidden="true" className="h-5 w-5" />
        {unreadCount > 0 ? (
          <span
            aria-hidden="true"
            className="absolute -right-0.5 -top-0.5 min-w-[1.25rem] rounded-full bg-red-600 px-1 text-center text-xs font-semibold leading-5 text-white"
          >
            {badgeLabel(unreadCount)}
          </span>
        ) : null}
      </button>

      {/* Announced on change, so a screen reader learns about new notifications without opening
          the panel — the badge alone is colour and shape. */}
      <span role="status" aria-live="polite" className="sr-only">
        {unreadText}
      </span>

      {open ? (
        <div
          id="notification-panel"
          className="absolute right-0 z-20 mt-2 w-80 rounded-xl border border-gray-200 bg-white shadow-popover"
        >
          <div className="flex items-center justify-between border-b border-gray-200 px-4 py-2">
            <h2 className="text-sm font-semibold text-gray-900">Notifications</h2>
            {notificationsQuery.isFetching ? (
              <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin text-gray-400" />
            ) : null}
          </div>

          {notificationsQuery.isError ? (
            <p role="alert" className="px-4 py-6 text-sm text-danger-700">
              Could not load notifications.
            </p>
          ) : notificationsQuery.isPending ? (
            <p className="px-4 py-6 text-sm text-gray-500">Loading notifications…</p>
          ) : notifications.length === 0 ? (
            <p className="px-4 py-6 text-sm text-gray-500">You have no notifications.</p>
          ) : (
            <ul className="max-h-80 divide-y divide-gray-100 overflow-y-auto">
              {notifications.map((notification) => {
                const message = notificationMessage(notification);
                return (
                  <li key={notification.id}>
                    <button
                      type="button"
                      onClick={() => {
                        if (!notification.isRead) {
                          markRead.mutate(notification.id);
                        }
                      }}
                      disabled={notification.isRead}
                      className="w-full px-4 py-3 text-left hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-inset focus:ring-primary-500 disabled:cursor-default disabled:hover:bg-white"
                    >
                      <span className="flex items-baseline justify-between gap-2">
                        <span
                          className={`text-sm ${
                            notification.isRead ? "text-gray-600" : "font-semibold text-gray-900"
                          }`}
                        >
                          {notificationTitle(notification)}
                        </span>
                        {notification.isRead ? null : (
                          <span className="shrink-0 text-xs font-medium text-primary-600">Unread</span>
                        )}
                      </span>
                      {message ? (
                        <span className="mt-0.5 block text-sm text-gray-600">{message}</span>
                      ) : null}
                      <span className="mt-0.5 block text-xs text-gray-400">
                        {formatTimestamp(notification.createdAt)}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}

          <div className="border-t border-gray-200 px-4 py-2">
            <button
              type="button"
              onClick={() => {
                close();
                triggerRef.current?.focus();
              }}
              className="text-xs font-medium text-gray-600 hover:text-gray-900"
            >
              Close
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
}

export default NotificationBell;
