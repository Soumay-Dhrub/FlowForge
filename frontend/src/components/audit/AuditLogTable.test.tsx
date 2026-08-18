import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AxiosError, AxiosHeaders } from "axios";
import AuditLogTable from "@/components/audit/AuditLogTable";
import { AuthProvider } from "@/context/AuthContext";
import * as auditApi from "@/lib/auditApi";
import * as authApi from "@/lib/authApi";
import { setTokens } from "@/lib/tokenStorage";
import * as usersApi from "@/lib/usersApi";
import type { AuditLogEntry, AuditLogSearchPage, RoleName, User } from "@/types";

jest.mock("@/lib/authApi");
jest.mock("@/lib/auditApi", () => ({
  ...jest.requireActual("@/lib/auditApi"),
  searchAuditLogs: jest.fn(),
  exportAuditLogsCsv: jest.fn(),
}));
jest.mock("@/lib/usersApi", () => ({
  ...jest.requireActual("@/lib/usersApi"),
  fetchUsers: jest.fn(),
}));

const mockedAuthApi = jest.mocked(authApi);
const mockedAuditApi = jest.mocked(auditApi);
const mockedUsersApi = jest.mocked(usersApi);

const ACTOR_ID = "1b8f07a9-b45d-45df-9228-89c851309d89";

function caller(roleName: RoleName): User {
  return {
    id: ACTOR_ID,
    name: "FlowForge Admin",
    email: "admin@flowforge.local",
    roleId: `role-${roleName.toLowerCase()}`,
    roleName,
    departmentId: null,
    departmentName: null,
    isActive: true,
    createdAt: "2026-01-01T10:00:00Z",
    updatedAt: "2026-01-01T10:00:00Z",
  };
}

function entry(overrides: Partial<AuditLogEntry> = {}): AuditLogEntry {
  return {
    id: "audit-1",
    actorId: ACTOR_ID,
    action: "APPROVE_TASK",
    entityType: "Task",
    entityId: "task-9",
    beforeState: { status: "PENDING" },
    afterState: { status: "COMPLETED" },
    createdAt: "2026-02-01T10:00:00Z",
    ...overrides,
  };
}

function searchPage(overrides: Partial<AuditLogSearchPage> = {}): AuditLogSearchPage {
  return {
    entries: [entry()],
    totalCount: 1,
    page: 0,
    size: 25,
    ...overrides,
  };
}

/** A full page of distinct entries, so paging has something to page through. */
function fullPage(page: number, totalCount: number): AuditLogSearchPage {
  return {
    entries: Array.from({ length: 25 }, (_, index) =>
      entry({ id: `audit-${page}-${index}`, entityId: `task-${page}-${index}` }),
    ),
    totalCount,
    page,
    size: 25,
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

function renderTable(roleName: RoleName = "ADMIN") {
  setTokens({
    accessToken: "access-1",
    refreshToken: "refresh-1",
    tokenType: "Bearer",
    expiresIn: 900,
  });
  mockedAuthApi.fetchCurrentUser.mockResolvedValue(caller(roleName));

  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <AuditLogTable />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

describe("AuditLogTable", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    window.localStorage.clear();
    mockedAuditApi.searchAuditLogs.mockResolvedValue(searchPage());
    mockedAuditApi.exportAuditLogsCsv.mockResolvedValue(undefined);
    mockedUsersApi.fetchUsers.mockResolvedValue([caller("ADMIN")]);
  });

  it("renders entries in a real table and resolves the actor to a name", async () => {
    renderTable();

    const table = await screen.findByRole("table");
    ["When", "Actor", "Action", "Entity type", "Entity", "Changes"].forEach((header) => {
      expect(within(table).getByRole("columnheader", { name: header })).toBeInTheDocument();
    });
    expect(await within(table).findByText("FlowForge Admin")).toBeInTheDocument();
    expect(within(table).getByText("Approve task")).toBeInTheDocument();
    expect(within(table).getByText("Task")).toBeInTheDocument();
  });

  it("renders a null actor as an explicit system or deleted user, not a blank cell", async () => {
    // The audit FK is ON DELETE SET NULL: the trail outlives the accounts it describes, so a null
    // actor is a fact about the entry rather than a missing value.
    mockedAuditApi.searchAuditLogs.mockResolvedValue(
      searchPage({ entries: [entry({ id: "audit-null", actorId: null })] }),
    );

    renderTable();

    expect(await screen.findByText("System / deleted user")).toBeInTheDocument();
    expect(screen.getByRole("table")).toBeInTheDocument();
  });

  it("asks the server for the next page rather than slicing what it holds", async () => {
    mockedAuditApi.searchAuditLogs.mockImplementation(async (_filters, page = 0) =>
      fullPage(page ?? 0, 60),
    );

    renderTable();

    expect(await screen.findByText("Showing 1–25 of 60")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Previous page" })).toBeDisabled();

    await userEvent.click(screen.getByRole("button", { name: "Next page" }));

    await waitFor(() =>
      expect(mockedAuditApi.searchAuditLogs).toHaveBeenLastCalledWith(
        { userId: "", entityType: "", action: "", dateFrom: "", dateTo: "" },
        1,
      ),
    );
    expect(await screen.findByText("Showing 26–50 of 60")).toBeInTheDocument();
    expect(screen.getByText("Page 2")).toBeInTheDocument();
  });

  it("stops paging at the last page", async () => {
    mockedAuditApi.searchAuditLogs.mockResolvedValue(searchPage({ totalCount: 1 }));

    renderTable();

    expect(await screen.findByText("Showing 1–1 of 1")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next page" })).toBeDisabled();
  });

  it("filters server-side by entity type, action, user and date range (Requirement 19.3)", async () => {
    renderTable();
    await screen.findByRole("table");

    await userEvent.type(screen.getByLabelText("Entity type"), "Task");
    await waitFor(() =>
      expect(mockedAuditApi.searchAuditLogs).toHaveBeenLastCalledWith(
        expect.objectContaining({ entityType: "Task" }),
        0,
      ),
    );

    await userEvent.type(screen.getByLabelText("Action"), "APPROVE_TASK");
    await userEvent.type(screen.getByLabelText("From"), "2026-01-31");
    await userEvent.selectOptions(await screen.findByLabelText("User"), ACTOR_ID);

    await waitFor(() =>
      expect(mockedAuditApi.searchAuditLogs).toHaveBeenLastCalledWith(
        {
          userId: ACTOR_ID,
          entityType: "Task",
          action: "APPROVE_TASK",
          // A calendar date, which is all the endpoint accepts; an instant is a 400.
          dateFrom: "2026-01-31",
          dateTo: "",
        },
        0,
      ),
    );
  });

  it("returns to the first page when a filter changes", async () => {
    mockedAuditApi.searchAuditLogs.mockImplementation(async (_filters, page = 0) =>
      fullPage(page ?? 0, 60),
    );

    renderTable();
    await screen.findByText("Showing 1–25 of 60");
    await userEvent.click(screen.getByRole("button", { name: "Next page" }));
    await screen.findByText("Page 2");

    await userEvent.type(screen.getByLabelText("Entity type"), "T");

    await waitFor(() => expect(screen.getByText("Page 1")).toBeInTheDocument());
    expect(mockedAuditApi.searchAuditLogs).toHaveBeenLastCalledWith(
      expect.objectContaining({ entityType: "T" }),
      0,
    );
  });

  it("exports the filtered trail as CSV (Requirement 19.4)", async () => {
    renderTable();
    await screen.findByRole("table");
    await userEvent.type(screen.getByLabelText("Action"), "REJECT_TASK");

    await userEvent.click(screen.getByRole("button", { name: "Export CSV" }));

    await waitFor(() =>
      expect(mockedAuditApi.exportAuditLogsCsv).toHaveBeenCalledWith(
        expect.objectContaining({ action: "REJECT_TASK" }),
      ),
    );
  });

  it("surfaces a failed export without losing the table", async () => {
    mockedAuditApi.exportAuditLogsCsv.mockRejectedValue(new Error("Network down"));

    renderTable();
    await screen.findByRole("table");
    await userEvent.click(screen.getByRole("button", { name: "Export CSV" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Could not export the audit trail.");
    expect(screen.getByRole("table")).toBeInTheDocument();
  });

  it("distinguishes an empty result set from a failed request", async () => {
    mockedAuditApi.searchAuditLogs.mockResolvedValue(searchPage({ entries: [], totalCount: 0 }));

    renderTable();

    expect(await screen.findByText("No audit entries have been recorded yet.")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("refuses a non-admin who reached the page by URL, without calling the endpoint", async () => {
    renderTable("MANAGER");

    expect(await screen.findByRole("heading", { name: "Not authorized" })).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Only administrators can read the audit trail.",
    );
    expect(mockedAuditApi.searchAuditLogs).not.toHaveBeenCalled();
  });

  it("shows the same not-authorized state when the API answers 403", async () => {
    mockedAuditApi.searchAuditLogs.mockRejectedValue(forbidden());

    renderTable();

    expect(await screen.findByRole("heading", { name: "Not authorized" })).toBeInTheDocument();
    expect(screen.queryByText("Could not load the audit trail.")).not.toBeInTheDocument();
  });
});
