import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AxiosError, AxiosHeaders } from "axios";
import UserList from "@/components/users/UserList";
import { AuthProvider } from "@/context/AuthContext";
import * as authApi from "@/lib/authApi";
import * as referenceDataApi from "@/lib/referenceDataApi";
import { setTokens } from "@/lib/tokenStorage";
import * as usersApi from "@/lib/usersApi";
import type { RoleName, User } from "@/types";

jest.mock("@/lib/authApi");
jest.mock("@/lib/usersApi", () => ({
  ...jest.requireActual("@/lib/usersApi"),
  fetchUsers: jest.fn(),
  setUserStatus: jest.fn(),
}));
jest.mock("@/lib/referenceDataApi", () => ({
  ...jest.requireActual("@/lib/referenceDataApi"),
  fetchRoles: jest.fn(),
  fetchDepartments: jest.fn(),
}));

jest.mock("next/link", () => ({
  __esModule: true,
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const mockedAuthApi = jest.mocked(authApi);
const mockedUsersApi = jest.mocked(usersApi);
const mockedReferenceDataApi = jest.mocked(referenceDataApi);

function user(overrides: Partial<User> = {}): User {
  return {
    id: "11111111-1111-1111-1111-111111111111",
    name: "Grace Hopper",
    email: "grace@flowforge.local",
    roleId: "role-manager",
    roleName: "MANAGER",
    departmentId: "dept-1",
    departmentName: "Operations",
    isActive: true,
    createdAt: "2026-01-01T10:00:00Z",
    updatedAt: "2026-01-01T10:00:00Z",
    ...overrides,
  };
}

function forbidden(): AxiosError {
  const error = new AxiosError("Request failed with status code 403");
  error.response = {
    status: 403,
    statusText: "Forbidden",
    data: { success: false, message: "Access denied" },
    headers: {},
    config: { headers: new AxiosHeaders() },
  };
  return error;
}

function renderUsers(callerRole: RoleName) {
  setTokens({
    accessToken: "access-1",
    refreshToken: "refresh-1",
    tokenType: "Bearer",
    expiresIn: 900,
  });
  mockedAuthApi.fetchCurrentUser.mockResolvedValue(
    user({
      id: "99999999-9999-9999-9999-999999999999",
      name: "Ada Lovelace",
      email: "ada@flowforge.local",
      roleId: `role-${callerRole.toLowerCase()}`,
      roleName: callerRole,
    }),
  );

  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <UserList />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

describe("UserList", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    window.localStorage.clear();
    mockedUsersApi.fetchUsers.mockResolvedValue([]);
    mockedReferenceDataApi.fetchRoles.mockResolvedValue([]);
    mockedReferenceDataApi.fetchDepartments.mockResolvedValue([]);
  });

  it("renders each user as a row in a real table with column headers", async () => {
    mockedUsersApi.fetchUsers.mockResolvedValue([
      user(),
      user({
        id: "22222222-2222-2222-2222-222222222222",
        name: "Alan Turing",
        email: "alan@flowforge.local",
        isActive: false,
      }),
    ]);

    renderUsers("ADMIN");

    expect(await screen.findByRole("rowheader", { name: "Grace Hopper" })).toBeInTheDocument();
    const table = screen.getByRole("table");
    ["Name", "Email", "Role", "Department", "Status", "Created", "Actions"].forEach((header) => {
      expect(within(table).getByRole("columnheader", { name: header })).toBeInTheDocument();
    });
    expect(within(table).getByRole("rowheader", { name: "Alan Turing" })).toBeInTheDocument();
    expect(within(table).getByText("Active")).toBeInTheDocument();
    expect(within(table).getByText("Inactive")).toBeInTheDocument();
  });

  it("refuses a non-admin who reached the page by URL, without calling the endpoint", async () => {
    renderUsers("MANAGER");

    expect(await screen.findByRole("heading", { name: "Not authorized" })).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Only administrators can manage users.");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(mockedUsersApi.fetchUsers).not.toHaveBeenCalled();
  });

  it("shows the same not-authorized state when the API answers 403", async () => {
    mockedUsersApi.fetchUsers.mockRejectedValue(forbidden());

    renderUsers("ADMIN");

    expect(await screen.findByRole("heading", { name: "Not authorized" })).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    // Not the generic table error, and not a raw 403 body.
    expect(screen.queryByText("Could not load users.")).not.toBeInTheDocument();
  });

  it("deactivates an active account and says what that did to their sessions", async () => {
    mockedUsersApi.fetchUsers.mockResolvedValue([user()]);
    mockedUsersApi.setUserStatus.mockResolvedValue(user({ isActive: false }));

    renderUsers("ADMIN");
    await userEvent.click(await screen.findByRole("button", { name: "Deactivate Grace Hopper" }));

    await waitFor(() =>
      expect(mockedUsersApi.setUserStatus).toHaveBeenCalledWith(
        "11111111-1111-1111-1111-111111111111",
        false,
      ),
    );
    expect(await screen.findByRole("status")).toHaveTextContent(
      "Deactivated Grace Hopper. Their active sessions have been revoked.",
    );
  });

  it("reactivates an inactive account", async () => {
    mockedUsersApi.fetchUsers.mockResolvedValue([user({ isActive: false })]);
    mockedUsersApi.setUserStatus.mockResolvedValue(user({ isActive: true }));

    renderUsers("ADMIN");
    await userEvent.click(await screen.findByRole("button", { name: "Reactivate Grace Hopper" }));

    await waitFor(() =>
      expect(mockedUsersApi.setUserStatus).toHaveBeenCalledWith(
        "11111111-1111-1111-1111-111111111111",
        true,
      ),
    );
    expect(await screen.findByRole("status")).toHaveTextContent(
      "Reactivated Grace Hopper. They can sign in again.",
    );
  });

  it("keeps a failed status change visible as an error and leaves the row alone", async () => {
    mockedUsersApi.fetchUsers.mockResolvedValue([user()]);
    mockedUsersApi.setUserStatus.mockRejectedValue(new Error("Network down"));

    renderUsers("ADMIN");
    await userEvent.click(await screen.findByRole("button", { name: "Deactivate Grace Hopper" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Could not change that account's status.",
    );
    expect(screen.getByRole("button", { name: "Deactivate Grace Hopper" })).toBeInTheDocument();
  });

  it("distinguishes an empty user list from a failed request", async () => {
    renderUsers("ADMIN");

    expect(await screen.findByText("No users yet.")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
