import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import AppShell from "@/components/layout/AppShell";
import ProtectedRoute from "@/components/auth/ProtectedRoute";
import { AuthProvider } from "@/context/AuthContext";
import * as authApi from "@/lib/authApi";
import * as notificationsApi from "@/lib/notificationsApi";
import { setTokens } from "@/lib/tokenStorage";
import type { RoleName, User } from "@/types";

const replace = jest.fn();

jest.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: jest.fn() }),
  usePathname: () => "/dashboard",
}));

jest.mock("@/lib/authApi");
jest.mock("@/lib/notificationsApi", () => ({
  ...jest.requireActual("@/lib/notificationsApi"),
  fetchNotifications: jest.fn(),
  fetchUnreadCount: jest.fn(),
  markNotificationRead: jest.fn(),
}));

const mockedAuthApi = jest.mocked(authApi);
const mockedNotificationsApi = jest.mocked(notificationsApi);

function userWithRole(roleName: RoleName): User {
  return {
    id: "1b8f07a9-b45d-45df-9228-89c851309d89",
    name: "Ada Lovelace",
    email: "ada.lovelace@flowforge.local",
    roleId: "role-1",
    roleName,
    departmentId: "dept-1",
    departmentName: "Operations",
    isActive: true,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
  };
}

/** Exactly what the `(app)` route group's layout renders around a page. */
function renderShell() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <ProtectedRoute>
          <AppShell>
            <h1>Dashboard</h1>
          </AppShell>
        </ProtectedRoute>
      </AuthProvider>
    </QueryClientProvider>,
  );
}

describe("AppShell (the authenticated layout)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    window.localStorage.clear();
    mockedNotificationsApi.fetchUnreadCount.mockResolvedValue(0);
    mockedNotificationsApi.fetchNotifications.mockResolvedValue([]);
  });

  it("gives an ADMIN the full sidebar plus the bell and profile menu", async () => {
    setTokens({
      accessToken: "access-1",
      refreshToken: "refresh-1",
      tokenType: "Bearer",
      expiresIn: 900,
    });
    mockedAuthApi.fetchCurrentUser.mockResolvedValue(userWithRole("ADMIN"));

    renderShell();

    expect(await screen.findByRole("navigation", { name: "Main navigation" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Users" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Audit Logs" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Notifications/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Ada Lovelace/ })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Dashboard" })).toBeInTheDocument();
  });

  it("hides the ADMIN-only sections from an EMPLOYEE", async () => {
    setTokens({
      accessToken: "access-1",
      refreshToken: "refresh-1",
      tokenType: "Bearer",
      expiresIn: 900,
    });
    mockedAuthApi.fetchCurrentUser.mockResolvedValue(userWithRole("EMPLOYEE"));

    renderShell();

    expect(await screen.findByRole("link", { name: "Tasks" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Users" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Audit Logs" })).not.toBeInTheDocument();
  });

  it("renders no shell for a visitor with no session and sends them to login", async () => {
    renderShell();

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/login"));
    expect(screen.queryByRole("navigation", { name: "Main navigation" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Notifications/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Dashboard" })).not.toBeInTheDocument();
  });
});
