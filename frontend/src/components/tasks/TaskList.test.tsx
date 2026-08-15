import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import TaskList from "@/components/tasks/TaskList";
import { AuthProvider } from "@/context/AuthContext";
import * as authApi from "@/lib/authApi";
import { endOfDayInstant, startOfDayInstant } from "@/lib/tasksApi";
import * as tasksApi from "@/lib/tasksApi";
import { setTokens } from "@/lib/tokenStorage";
import * as workflowsApi from "@/lib/workflowsApi";
import type { RoleName, Task, User, Workflow } from "@/types";

jest.mock("@/lib/authApi");
jest.mock("@/lib/tasksApi", () => ({
  ...jest.requireActual("@/lib/tasksApi"),
  listTasks: jest.fn(),
}));
jest.mock("@/lib/workflowsApi", () => ({
  ...jest.requireActual("@/lib/workflowsApi"),
  fetchWorkflows: jest.fn(),
}));

jest.mock("next/link", () => ({
  __esModule: true,
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const mockedTasksApi = jest.mocked(tasksApi);
const mockedWorkflowsApi = jest.mocked(workflowsApi);
const mockedAuthApi = jest.mocked(authApi);

const ASSIGNEE_ID = "22222222-2222-2222-2222-222222222222";

function userWithRole(roleName: RoleName): User {
  return {
    id: ASSIGNEE_ID,
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

function task(overrides: Partial<Task> = {}): Task {
  return {
    id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    instanceId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
    workflowId: "11111111-1111-1111-1111-111111111111",
    workflowName: "Expense approval",
    nodeId: "cccccccc-cccc-cccc-cccc-cccccccccccc",
    nodeType: "APPROVAL",
    nodeLabel: "Manager sign-off",
    assignedToId: ASSIGNEE_ID,
    status: "PENDING",
    dueAt: "2026-01-05T09:00:00Z",
    decision: null,
    comment: null,
    createdAt: "2026-01-02T09:00:00Z",
    ...overrides,
  };
}

function workflow(overrides: Partial<Workflow> = {}): Workflow {
  return {
    id: "11111111-1111-1111-1111-111111111111",
    name: "Expense approval",
    description: null,
    status: "ACTIVE",
    createdById: ASSIGNEE_ID,
    createdByName: "Ada Lovelace",
    createdAt: "2026-01-01T10:00:00Z",
    updatedAt: "2026-01-01T10:00:00Z",
    versions: null,
    ...overrides,
  };
}

function renderList(role: RoleName = "EMPLOYEE") {
  setTokens({
    accessToken: "access-1",
    refreshToken: "refresh-1",
    tokenType: "Bearer",
    expiresIn: 900,
  });
  mockedAuthApi.fetchCurrentUser.mockResolvedValue(userWithRole(role));

  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <TaskList />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

describe("TaskList", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    window.localStorage.clear();
    mockedTasksApi.listTasks.mockResolvedValue([]);
    mockedWorkflowsApi.fetchWorkflows.mockResolvedValue([workflow()]);
  });

  it("renders each task as a row in a real table with column headers", async () => {
    mockedTasksApi.listTasks.mockResolvedValue([
      task(),
      task({
        id: "dddddddd-dddd-dddd-dddd-dddddddddddd",
        workflowName: "Leave request",
        nodeLabel: null,
        nodeType: "TASK",
        status: "COMPLETED",
        decision: "APPROVED",
        dueAt: null,
      }),
    ]);

    renderList();

    const table = await screen.findByRole("table");
    ["Workflow", "Step", "Status", "Due", "Raised"].forEach((header) => {
      expect(within(table).getByRole("columnheader", { name: header })).toBeInTheDocument();
    });
    expect(within(table).getByRole("rowheader", { name: /Expense approval/ })).toBeInTheDocument();
    expect(within(table).getByRole("rowheader", { name: /Leave request/ })).toBeInTheDocument();
    expect(within(table).getByText("Manager sign-off")).toBeInTheDocument();
    expect(within(table).getByText("Pending")).toBeInTheDocument();
    expect(within(table).getByText("Completed")).toBeInTheDocument();
    // The row without a configured label falls back to its node type rather than showing nothing.
    expect(within(table).getByText("TASK")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Expense approval" })).toHaveAttribute(
      "href",
      "/tasks/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    );
  });

  it("filters by status through the backend rather than in the browser", async () => {
    mockedTasksApi.listTasks.mockResolvedValue([task()]);

    renderList();
    await screen.findByRole("rowheader", { name: /Expense approval/ });

    await userEvent.selectOptions(screen.getByLabelText("Status"), "ESCALATED");

    await waitFor(() =>
      expect(mockedTasksApi.listTasks).toHaveBeenLastCalledWith({
        status: "ESCALATED",
        workflowId: "",
        createdFrom: "",
        createdTo: "",
      }),
    );
  });

  it("sends a created date range that covers both whole days", async () => {
    renderList();
    await screen.findByRole("table");

    fireEvent.change(screen.getByLabelText("Raised from"), { target: { value: "2026-01-02" } });
    fireEvent.change(screen.getByLabelText("Raised to"), { target: { value: "2026-01-03" } });

    await waitFor(() =>
      expect(mockedTasksApi.listTasks).toHaveBeenLastCalledWith({
        status: "",
        workflowId: "",
        createdFrom: startOfDayInstant("2026-01-02"),
        createdTo: endOfDayInstant("2026-01-03"),
      }),
    );
  });

  it("offers the workflow filter to a manager and passes the chosen workflow to the backend", async () => {
    renderList("MANAGER");

    const workflowFilter = await screen.findByLabelText("Workflow");
    await waitFor(() => expect(workflowFilter).not.toBeDisabled());
    await userEvent.selectOptions(workflowFilter, "11111111-1111-1111-1111-111111111111");

    await waitFor(() =>
      expect(mockedTasksApi.listTasks).toHaveBeenLastCalledWith({
        status: "",
        workflowId: "11111111-1111-1111-1111-111111111111",
        createdFrom: "",
        createdTo: "",
      }),
    );
  });

  it("hides the workflow filter from an employee instead of firing a request that would be refused", async () => {
    renderList("EMPLOYEE");

    await screen.findByRole("table");
    await waitFor(() => expect(screen.getByLabelText("Status")).toBeInTheDocument());
    expect(screen.queryByLabelText("Workflow")).not.toBeInTheDocument();
    expect(mockedWorkflowsApi.fetchWorkflows).not.toHaveBeenCalled();
  });

  it("distinguishes an empty filtered result from an empty queue", async () => {
    renderList();

    expect(
      await screen.findByText("You have no tasks. Anything assigned to you will appear here."),
    ).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText("Status"), "COMPLETED");

    expect(await screen.findByText("No tasks match these filters.")).toBeInTheDocument();
  });

  it("reports a failed request as an error, not as an empty queue", async () => {
    mockedTasksApi.listTasks.mockRejectedValue(new Error("Network down"));

    renderList();

    expect(await screen.findByRole("alert")).toHaveTextContent("Could not load tasks.");
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
    expect(
      screen.queryByText("You have no tasks. Anything assigned to you will appear here."),
    ).not.toBeInTheDocument();
  });
});
