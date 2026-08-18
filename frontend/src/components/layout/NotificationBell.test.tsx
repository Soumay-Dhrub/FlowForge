import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import NotificationBell from "@/components/layout/NotificationBell";
import * as notificationsApi from "@/lib/notificationsApi";
import type { Notification } from "@/types";
import { createTestQueryClient } from "@/test/renderWithQuery";

jest.mock("@/lib/notificationsApi", () => ({
  ...jest.requireActual("@/lib/notificationsApi"),
  fetchNotifications: jest.fn(),
  fetchUnreadCount: jest.fn(),
  markNotificationRead: jest.fn(),
}));

const mockedApi = jest.mocked(notificationsApi);

function notification(overrides: Partial<Notification> = {}): Notification {
  return {
    id: "11111111-1111-1111-1111-111111111111",
    eventType: "TASK_ASSIGNED",
    payload: { message: "A task was assigned to you." },
    isRead: false,
    createdAt: "2026-01-01T10:00:00Z",
    ...overrides,
  };
}

function renderBell() {
  const queryClient = createTestQueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <NotificationBell />
    </QueryClientProvider>,
  );
}

describe("NotificationBell", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockedApi.fetchUnreadCount.mockResolvedValue(0);
    mockedApi.fetchNotifications.mockResolvedValue([]);
  });

  it("announces the unread count rather than relying on the badge alone", async () => {
    mockedApi.fetchUnreadCount.mockResolvedValue(3);

    renderBell();

    const bell = await screen.findByRole("button", {
      name: "Notifications (3 unread notifications)",
    });
    expect(bell).toHaveAttribute("aria-expanded", "false");
    expect(await screen.findByRole("status")).toHaveTextContent("3 unread notifications");
  });

  it("does not fetch the list until the dropdown is opened", async () => {
    mockedApi.fetchUnreadCount.mockResolvedValue(1);
    mockedApi.fetchNotifications.mockResolvedValue([notification()]);

    renderBell();
    await screen.findByRole("status");
    expect(mockedApi.fetchNotifications).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole("button", { name: /Notifications/ }));

    expect(await screen.findByText("A task was assigned to you.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Notifications/ })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
  });

  it("marks a notification read optimistically, before the request settles", async () => {
    mockedApi.fetchUnreadCount.mockResolvedValue(1);
    mockedApi.fetchNotifications.mockResolvedValue([notification()]);
    // Never settles: anything the UI shows now is the optimistic update, not the response.
    mockedApi.markNotificationRead.mockReturnValue(new Promise<Notification>(() => {}));

    renderBell();
    await userEvent.click(await screen.findByRole("button", { name: /Notifications/ }));
    await userEvent.click(await screen.findByRole("button", { name: /Task assigned/ }));

    await waitFor(() => expect(screen.queryByText("Unread")).not.toBeInTheDocument());
    expect(mockedApi.markNotificationRead).toHaveBeenCalledWith(notification().id);
    expect(
      screen.getByRole("button", { name: "Notifications (No unread notifications)" }),
    ).toBeInTheDocument();
  });

  it("rolls the optimistic update back when marking read fails", async () => {
    mockedApi.fetchUnreadCount.mockResolvedValue(1);
    mockedApi.fetchNotifications.mockResolvedValue([notification()]);
    mockedApi.markNotificationRead.mockRejectedValue(new Error("Forbidden"));

    renderBell();
    await userEvent.click(await screen.findByRole("button", { name: /Notifications/ }));
    await userEvent.click(await screen.findByRole("button", { name: /Task assigned/ }));

    await waitFor(() => expect(mockedApi.markNotificationRead).toHaveBeenCalled());
    expect(await screen.findByText("Unread")).toBeInTheDocument();
    expect(
      await screen.findByRole("button", { name: "Notifications (1 unread notification)" }),
    ).toBeInTheDocument();
  });

  it("closes on Escape and returns focus to the bell", async () => {
    mockedApi.fetchNotifications.mockResolvedValue([notification()]);

    renderBell();
    const bell = await screen.findByRole("button", { name: /Notifications/ });
    await userEvent.click(bell);
    expect(await screen.findByText("Notifications")).toBeInTheDocument();

    await userEvent.keyboard("{Escape}");

    await waitFor(() => expect(bell).toHaveAttribute("aria-expanded", "false"));
    expect(bell).toHaveFocus();
  });

  it("reports an empty inbox rather than showing nothing", async () => {
    renderBell();

    await userEvent.click(await screen.findByRole("button", { name: /Notifications/ }));

    expect(await screen.findByText("You have no notifications.")).toBeInTheDocument();
  });
});
