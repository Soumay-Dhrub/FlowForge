import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AxiosError, AxiosHeaders } from "axios";
import TaskDetail from "@/components/tasks/TaskDetail";
import { AuthProvider } from "@/context/AuthContext";
import * as authApi from "@/lib/authApi";
import * as instancesApi from "@/lib/instancesApi";
import * as tasksApi from "@/lib/tasksApi";
import { setTokens } from "@/lib/tokenStorage";
import type { RoleName, Task, User, WorkflowInstance } from "@/types";

jest.mock("@/lib/authApi");
jest.mock("@/lib/tasksApi", () => ({
  ...jest.requireActual("@/lib/tasksApi"),
  getTask: jest.fn(),
  recordDecision: jest.fn(),
}));
jest.mock("@/lib/instancesApi", () => ({
  ...jest.requireActual("@/lib/instancesApi"),
  fetchInstance: jest.fn(),
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
const mockedInstancesApi = jest.mocked(instancesApi);
const mockedAuthApi = jest.mocked(authApi);

const TASK_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const INSTANCE_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
const ASSIGNEE_ID = "22222222-2222-2222-2222-222222222222";
const SOMEONE_ELSE_ID = "99999999-9999-9999-9999-999999999999";

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
    id: TASK_ID,
    instanceId: INSTANCE_ID,
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

function instance(overrides: Partial<WorkflowInstance> = {}): WorkflowInstance {
  return {
    id: INSTANCE_ID,
    workflowId: "11111111-1111-1111-1111-111111111111",
    workflowName: "Expense approval",
    workflowVersionId: "dddddddd-dddd-dddd-dddd-dddddddddddd",
    versionNumber: 3,
    initiatedById: SOMEONE_ELSE_ID,
    initiatorName: "Grace Hopper",
    status: "RUNNING",
    currentNodeId: "cccccccc-cccc-cccc-cccc-cccccccccccc",
    requestData: {
      amount: 249.5,
      currency: "GBP",
      urgent: true,
      lineItems: [{ description: "Taxi", amount: 30 }],
      note: null,
    },
    startedAt: "2026-01-02T08:55:00Z",
    completedAt: null,
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

function badRequest(message: string): AxiosError {
  const error = new AxiosError("Request failed with status code 400");
  error.response = {
    status: 400,
    statusText: "Bad Request",
    data: { success: false, message },
    headers: {},
    config: { headers: new AxiosHeaders() },
  };
  return error;
}

function conflict(message: string): AxiosError {
  const error = new AxiosError("Request failed with status code 409");
  error.response = {
    status: 409,
    statusText: "Conflict",
    data: { success: false, message },
    headers: {},
    config: { headers: new AxiosHeaders() },
  };
  return error;
}

function renderDetail(role: RoleName = "MANAGER") {
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
        <TaskDetail taskId={TASK_ID} />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

describe("TaskDetail", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    window.localStorage.clear();
    mockedTasksApi.getTask.mockResolvedValue(task());
    mockedInstancesApi.fetchInstance.mockResolvedValue(instance());
  });

  it("shows the task metadata and renders the submitted request payload generically", async () => {
    renderDetail();

    expect(await screen.findByRole("heading", { name: "Expense approval" })).toBeInTheDocument();
    expect(screen.getByText("Manager sign-off")).toBeInTheDocument();
    expect(screen.getByText("Pending")).toBeInTheDocument();

    await waitFor(() => expect(screen.getByText("Grace Hopper")).toBeInTheDocument());
    // Keys are whatever the requester submitted, so they are shown as they came.
    expect(screen.getByText("amount")).toBeInTheDocument();
    expect(screen.getByText("249.5")).toBeInTheDocument();
    expect(screen.getByText("currency")).toBeInTheDocument();
    expect(screen.getByText("GBP")).toBeInTheDocument();
    expect(screen.getByText("Yes")).toBeInTheDocument();
    expect(screen.getByText(/"description": "Taxi"/)).toBeInTheDocument();
  });

  it("refuses a rejection without a comment before calling the API", async () => {
    renderDetail();

    await userEvent.click(await screen.findByLabelText(/Reject/));
    await userEvent.click(screen.getByRole("button", { name: "Record decision" }));

    expect(
      await screen.findByText("A comment is required when rejecting a task"),
    ).toBeInTheDocument();
    expect(mockedTasksApi.recordDecision).not.toHaveBeenCalled();
    expect(screen.getByLabelText("Comment")).toHaveAttribute("aria-invalid", "true");
  });

  it("treats a whitespace-only rejection comment as no comment at all", async () => {
    renderDetail();

    await userEvent.click(await screen.findByLabelText(/Reject/));
    await userEvent.type(screen.getByLabelText("Comment"), "   ");
    await userEvent.click(screen.getByRole("button", { name: "Record decision" }));

    expect(
      await screen.findByText("A comment is required when rejecting a task"),
    ).toBeInTheDocument();
    expect(mockedTasksApi.recordDecision).not.toHaveBeenCalled();
  });

  it("records an approval and reports it", async () => {
    mockedTasksApi.recordDecision.mockResolvedValue(
      task({ status: "COMPLETED", decision: "APPROVED", comment: "Looks fine" }),
    );

    renderDetail();

    await userEvent.click(await screen.findByLabelText(/Approve/));
    await userEvent.type(screen.getByLabelText("Comment"), "Looks fine");
    await userEvent.click(screen.getByRole("button", { name: "Record decision" }));

    await waitFor(() =>
      expect(mockedTasksApi.recordDecision).toHaveBeenCalledWith(TASK_ID, "APPROVED", "Looks fine"),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("Recorded approved.");
  });

  it("approves without a comment, since only a rejection requires one", async () => {
    mockedTasksApi.recordDecision.mockResolvedValue(
      task({ status: "COMPLETED", decision: "APPROVED" }),
    );

    renderDetail();

    await userEvent.click(await screen.findByLabelText(/Approve/));
    await userEvent.click(screen.getByRole("button", { name: "Record decision" }));

    await waitFor(() =>
      expect(mockedTasksApi.recordDecision).toHaveBeenCalledWith(TASK_ID, "APPROVED", ""),
    );
  });

  it("puts a server-side 400 on the comment field", async () => {
    mockedTasksApi.recordDecision.mockRejectedValue(
      badRequest("A comment is required when rejecting a task"),
    );

    renderDetail();

    await userEvent.click(await screen.findByLabelText(/Reject/));
    await userEvent.type(screen.getByLabelText("Comment"), "no");
    await userEvent.click(screen.getByRole("button", { name: "Record decision" }));

    await waitFor(() => expect(mockedTasksApi.recordDecision).toHaveBeenCalled());
    expect(
      await screen.findByText("A comment is required when rejecting a task"),
    ).toBeInTheDocument();
  });

  it("surfaces an already-decided conflict with the server's own wording", async () => {
    mockedTasksApi.recordDecision.mockRejectedValue(
      conflict(`Task ${TASK_ID} is COMPLETED and cannot be decided`),
    );

    renderDetail();

    await userEvent.click(await screen.findByLabelText(/Approve/));
    await userEvent.click(screen.getByRole("button", { name: "Record decision" }));

    expect(
      await screen.findByText(`Task ${TASK_ID} is COMPLETED and cannot be decided`),
    ).toBeInTheDocument();
  });

  it("shows the recorded decision instead of a form once the task is decided", async () => {
    mockedTasksApi.getTask.mockResolvedValue(
      task({ status: "COMPLETED", decision: "REJECTED", comment: "Over budget" }),
    );

    renderDetail();

    expect(await screen.findByText("Rejected")).toBeInTheDocument();
    expect(screen.getByText("Over budget")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Record decision" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Comment")).not.toBeInTheDocument();
  });

  it("does not offer a form for a pending task that belongs to somebody else", async () => {
    mockedTasksApi.getTask.mockResolvedValue(task({ assignedToId: SOMEONE_ELSE_ID }));

    renderDetail();

    expect(
      await screen.findByText("This task is assigned to someone else, so only they can decide it."),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Record decision" })).not.toBeInTheDocument();
  });

  it("keeps the decision form usable when the request payload is not readable by this caller", async () => {
    mockedInstancesApi.fetchInstance.mockRejectedValue(forbidden());

    renderDetail("EMPLOYEE");

    expect(
      await screen.findByText(
        /You do not have access to this request's details\. You can still record your decision below\./,
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Record decision" })).toBeInTheDocument();
  });
});
