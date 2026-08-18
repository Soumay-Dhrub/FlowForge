import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import DashboardOverview from "@/components/reports/DashboardOverview";
import { AuthProvider } from "@/context/AuthContext";
import * as authApi from "@/lib/authApi";
import * as reportsApi from "@/lib/reportsApi";
import { setTokens } from "@/lib/tokenStorage";
import type { AuditEvent, Dashboard, Task, User, WorkflowInstance } from "@/types";
import { createTestQueryClient } from "@/test/renderWithQuery";

jest.mock("@/lib/authApi");
jest.mock("@/lib/reportsApi", () => ({
  ...jest.requireActual("@/lib/reportsApi"),
  fetchDashboard: jest.fn(),
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
const mockedReportsApi = jest.mocked(reportsApi);

const CALLER: User = {
  id: "99999999-9999-9999-9999-999999999999",
  name: "Ada Lovelace",
  email: "ada@flowforge.local",
  roleId: "role-employee",
  roleName: "EMPLOYEE",
  departmentId: null,
  departmentName: null,
  isActive: true,
  createdAt: "2026-01-01T10:00:00Z",
  updatedAt: "2026-01-01T10:00:00Z",
};

function task(overrides: Partial<Task> = {}): Task {
  return {
    id: "task-1",
    instanceId: "instance-1",
    workflowId: "workflow-1",
    workflowName: "Expense Approval",
    nodeId: "node-1",
    nodeType: "APPROVAL",
    nodeLabel: "Line manager review",
    assignedToId: CALLER.id,
    status: "PENDING",
    dueAt: null,
    decision: null,
    comment: null,
    createdAt: "2026-02-01T09:00:00Z",
    ...overrides,
  };
}

function instance(overrides: Partial<WorkflowInstance> = {}): WorkflowInstance {
  return {
    id: "instance-1",
    workflowId: "workflow-1",
    workflowName: "Expense Approval",
    workflowVersionId: "version-1",
    versionNumber: 3,
    initiatedById: CALLER.id,
    initiatorName: "Ada Lovelace",
    status: "RUNNING",
    currentNodeId: "node-1",
    requestData: null,
    startedAt: "2026-02-01T08:00:00Z",
    completedAt: null,
    ...overrides,
  };
}

function auditEvent(overrides: Partial<AuditEvent> = {}): AuditEvent {
  return {
    id: "audit-1",
    actorId: CALLER.id,
    action: "APPROVE_TASK",
    entityType: "Task",
    entityId: "task-9",
    createdAt: "2026-02-01T10:00:00Z",
    ...overrides,
  };
}

function dashboard(overrides: Partial<Dashboard> = {}): Dashboard {
  return {
    pendingTaskCount: 0,
    pendingTasks: [],
    submittedInstances: [],
    recentActivity: [],
    ...overrides,
  };
}

function renderDashboard() {
  setTokens({
    accessToken: "access-1",
    refreshToken: "refresh-1",
    tokenType: "Bearer",
    expiresIn: 900,
  });
  mockedAuthApi.fetchCurrentUser.mockResolvedValue(CALLER);

  const queryClient = createTestQueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <DashboardOverview />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

describe("DashboardOverview", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    window.localStorage.clear();
    mockedReportsApi.fetchDashboard.mockResolvedValue(dashboard());
  });

  it("renders the three widgets from a single dashboard request", async () => {
    mockedReportsApi.fetchDashboard.mockResolvedValue(
      dashboard({
        pendingTaskCount: 2,
        pendingTasks: [
          task(),
          task({
            id: "task-2",
            nodeLabel: null,
            nodeType: "TASK",
            status: "ESCALATED",
            workflowName: "Purchase Order",
            dueAt: "2026-02-05T17:00:00Z",
          }),
        ],
        submittedInstances: [
          instance(),
          instance({
            id: "instance-2",
            status: "REJECTED",
            workflowName: "Travel Request",
            completedAt: "2026-02-02T12:00:00Z",
          }),
        ],
        recentActivity: [auditEvent(), auditEvent({ id: "audit-2", action: "CREATE_INSTANCE" })],
      }),
    );

    renderDashboard();

    // Pending tasks (Requirement 20.1): both the count and the list.
    expect(await screen.findByRole("heading", { name: "Pending tasks" })).toBeInTheDocument();
    expect(screen.getByText("2 tasks are waiting on you.")).toBeInTheDocument();
    expect(screen.getByRole("rowheader", { name: "Line manager review" })).toBeInTheDocument();
    // A node the designer never labelled falls back to its type rather than rendering blank.
    expect(screen.getByRole("rowheader", { name: "TASK" })).toBeInTheDocument();
    expect(screen.getByText("Escalated")).toBeInTheDocument();

    // Submitted requests with their current status (Requirement 20.2).
    expect(screen.getByRole("heading", { name: "Submitted requests" })).toBeInTheDocument();
    expect(screen.getByText("Rejected")).toBeInTheDocument();
    expect(screen.getByRole("rowheader", { name: /Travel Request/ })).toBeInTheDocument();

    // Recent activity (Requirement 20.3), with action codes rendered as prose.
    expect(screen.getByRole("heading", { name: "Recent activity" })).toBeInTheDocument();
    expect(screen.getByText("Approve task")).toBeInTheDocument();
    expect(screen.getByText("Create instance")).toBeInTheDocument();

    expect(mockedReportsApi.fetchDashboard).toHaveBeenCalledTimes(1);
  });

  it("links a pending task to the page where it can be decided", async () => {
    mockedReportsApi.fetchDashboard.mockResolvedValue(
      dashboard({ pendingTaskCount: 1, pendingTasks: [task()] }),
    );

    renderDashboard();

    expect(await screen.findByRole("link", { name: "Line manager review" })).toHaveAttribute(
      "href",
      "/tasks/task-1",
    );
    expect(screen.getByText("1 task is waiting on you.")).toBeInTheDocument();
  });

  it("distinguishes an empty dashboard from a failed one", async () => {
    renderDashboard();

    expect(await screen.findByText("Nothing is waiting on you right now.")).toBeInTheDocument();
    expect(screen.getByText("You have not submitted any requests yet.")).toBeInTheDocument();
    expect(screen.getByText("No recorded activity yet.")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("reports a failed load as an error with a way to retry", async () => {
    mockedReportsApi.fetchDashboard.mockRejectedValue(new Error("Network down"));

    renderDashboard();

    expect(await screen.findByRole("alert")).toHaveTextContent("Could not load your dashboard.");
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("gives each widget's table real column headers", async () => {
    mockedReportsApi.fetchDashboard.mockResolvedValue(
      dashboard({ pendingTaskCount: 1, pendingTasks: [task()], submittedInstances: [instance()] }),
    );

    renderDashboard();

    const [pending, submitted] = await screen.findAllByRole("table");
    ["Stage", "Workflow", "Status", "Raised", "Due"].forEach((header) => {
      expect(within(pending).getByRole("columnheader", { name: header })).toBeInTheDocument();
    });
    ["Workflow", "Status", "Submitted", "Finished"].forEach((header) => {
      expect(within(submitted).getByRole("columnheader", { name: header })).toBeInTheDocument();
    });
  });
});
