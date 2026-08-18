import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AxiosError, AxiosHeaders } from "axios";
import WorkflowPerformanceReport from "@/components/reports/WorkflowPerformanceReport";
import { AuthProvider } from "@/context/AuthContext";
import * as authApi from "@/lib/authApi";
import * as referenceDataApi from "@/lib/referenceDataApi";
import * as reportsApi from "@/lib/reportsApi";
import { setTokens } from "@/lib/tokenStorage";
import type { NodePerformance, RoleName, User, WorkflowPerformance } from "@/types";

jest.mock("@/lib/authApi");
jest.mock("@/lib/reportsApi", () => ({
  ...jest.requireActual("@/lib/reportsApi"),
  fetchWorkflowPerformance: jest.fn(),
  exportPerformanceCsv: jest.fn(),
  exportPerformanceJson: jest.fn(),
}));
jest.mock("@/lib/referenceDataApi", () => ({
  ...jest.requireActual("@/lib/referenceDataApi"),
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
const mockedReportsApi = jest.mocked(reportsApi);
const mockedReferenceDataApi = jest.mocked(referenceDataApi);

const WORKFLOW_ID = "1c58d546-ed0a-4838-bcf0-3de56a80d5ba";

function node(overrides: Partial<NodePerformance> = {}): NodePerformance {
  return {
    nodeId: "node-1",
    nodeType: "APPROVAL",
    nodeLabel: "Line manager review",
    decidedTaskCount: 4,
    averageDwellSeconds: 7200,
    bottleneck: false,
    ...overrides,
  };
}

function report(overrides: Partial<WorkflowPerformance> = {}): WorkflowPerformance {
  return {
    workflowId: WORKFLOW_ID,
    workflowName: "Expense Approval",
    filters: {
      departmentId: null,
      workflowId: null,
      submittedFrom: null,
      submittedTo: null,
      minBottleneckSamples: 2,
    },
    totalInstanceVolume: 10,
    runningInstanceCount: 2,
    completedInstanceCount: 6,
    rejectedInstanceCount: 2,
    cancelledInstanceCount: 0,
    erroredInstanceCount: 0,
    decidedInstanceCount: 8,
    averageApprovalTimeSeconds: 9000,
    rejectionRate: 0.25,
    nodes: [node()],
    bottleneckNode: null,
    bottleneckMinimumSamples: 2,
    ...overrides,
  };
}

/** A report over a population the filters emptied: every average is null, every count is zero. */
function emptyReport(): WorkflowPerformance {
  return report({
    totalInstanceVolume: 0,
    runningInstanceCount: 0,
    completedInstanceCount: 0,
    rejectedInstanceCount: 0,
    cancelledInstanceCount: 0,
    erroredInstanceCount: 0,
    decidedInstanceCount: 0,
    averageApprovalTimeSeconds: null,
    rejectionRate: null,
    nodes: [],
    bottleneckNode: null,
  });
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

function caller(roleName: RoleName): User {
  return {
    id: "99999999-9999-9999-9999-999999999999",
    name: "Ada Lovelace",
    email: "ada@flowforge.local",
    roleId: `role-${roleName.toLowerCase()}`,
    roleName,
    departmentId: null,
    departmentName: null,
    isActive: true,
    createdAt: "2026-01-01T10:00:00Z",
    updatedAt: "2026-01-01T10:00:00Z",
  };
}

function renderReport(roleName: RoleName = "ADMIN") {
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
        <WorkflowPerformanceReport workflowId={WORKFLOW_ID} />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

describe("WorkflowPerformanceReport", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    window.localStorage.clear();
    mockedReportsApi.fetchWorkflowPerformance.mockResolvedValue(report());
    mockedReportsApi.exportPerformanceCsv.mockResolvedValue(undefined);
    mockedReferenceDataApi.fetchDepartments.mockResolvedValue([
      { id: "dept-1", name: "Operations" },
    ]);
  });

  it("renders the KPI cards from the report (Requirements 21.1, 21.3)", async () => {
    renderReport();

    expect(await screen.findByRole("heading", { name: "Expense Approval" })).toBeInTheDocument();
    expect(screen.getByText("Average approval time")).toBeInTheDocument();
    expect(screen.getByText("2 h 30 min")).toBeInTheDocument();
    expect(screen.getByText("Rejection rate")).toBeInTheDocument();
    expect(screen.getByText("25.0%")).toBeInTheDocument();
    expect(screen.getByText("Total volume")).toBeInTheDocument();
    expect(screen.getByText("10")).toBeInTheDocument();
    expect(screen.getByText("2 rejected of 8 decided.")).toBeInTheDocument();
  });

  it("renders a null average and a null rejection rate as no data, never as zero", async () => {
    // The endpoint returns null for an average over an empty population on purpose: rendering it as
    // "0 s" and "0.0%" would claim instantaneous approvals and a spotless rejection record for data
    // that does not exist.
    mockedReportsApi.fetchWorkflowPerformance.mockResolvedValue(emptyReport());

    renderReport();

    const noData = await screen.findAllByText("No data");
    expect(noData).toHaveLength(2);
    expect(screen.getByText("No request has been decided in this window.")).toBeInTheDocument();
    expect(
      screen.getByText("Nothing has been decided, so there is no rate to report."),
    ).toBeInTheDocument();

    expect(screen.queryByText("0.0%")).not.toBeInTheDocument();
    expect(screen.queryByText("0%")).not.toBeInTheDocument();
    expect(screen.queryByText("0 s")).not.toBeInTheDocument();
    expect(screen.queryByText("0 min")).not.toBeInTheDocument();

    // The volume card still shows a real zero, because zero is the true count.
    expect(screen.getByText("0")).toBeInTheDocument();
    expect(
      screen.getByText(
        "No stage has a decided task in this window, so there is no dwell time to measure.",
      ),
    ).toBeInTheDocument();
  });

  it("highlights the bottleneck stage in the panel and in the table (Requirement 21.2)", async () => {
    const slowest = node({
      nodeId: "node-slow",
      nodeLabel: "Finance review",
      averageDwellSeconds: 172800,
      decidedTaskCount: 5,
      bottleneck: true,
    });
    mockedReportsApi.fetchWorkflowPerformance.mockResolvedValue(
      report({ nodes: [slowest, node()], bottleneckNode: slowest }),
    );

    renderReport();

    expect(await screen.findByText("Bottleneck stage")).toBeInTheDocument();
    expect(screen.getByText("2 d average dwell over 5 decided tasks (APPROVAL).")).toBeInTheDocument();

    const flagged = screen.getByRole("rowheader", { name: /Finance review/ });
    expect(within(flagged).getByText("Bottleneck")).toBeInTheDocument();
    expect(
      within(screen.getByRole("rowheader", { name: "Line manager review" })).queryByText(
        "Bottleneck",
      ),
    ).not.toBeInTheDocument();
  });

  it("explains a null bottleneck by its sample threshold instead of naming a stage", async () => {
    mockedReportsApi.fetchWorkflowPerformance.mockResolvedValue(
      report({ bottleneckNode: null, bottleneckMinimumSamples: 3 }),
    );

    renderReport();

    expect(await screen.findByText(/No bottleneck stage yet/)).toHaveTextContent(
      "no stage has reached 3 decided tasks",
    );
    expect(screen.queryByText("Bottleneck stage")).not.toBeInTheDocument();
  });

  it("sends the date filters as calendar dates, which is all the endpoint accepts", async () => {
    renderReport();
    await screen.findByRole("heading", { name: "Expense Approval" });

    await userEvent.type(screen.getByLabelText("Submitted from"), "2026-01-31");

    await waitFor(() =>
      expect(mockedReportsApi.fetchWorkflowPerformance).toHaveBeenLastCalledWith(WORKFLOW_ID, {
        departmentId: "",
        dateFrom: "2026-01-31",
        dateTo: "",
        minSamples: "",
      }),
    );
  });

  it("re-requests the report when the department filter changes (Requirement 21.4)", async () => {
    renderReport();
    await screen.findByRole("heading", { name: "Expense Approval" });

    await userEvent.selectOptions(await screen.findByLabelText("Department"), "dept-1");

    await waitFor(() =>
      expect(mockedReportsApi.fetchWorkflowPerformance).toHaveBeenLastCalledWith(WORKFLOW_ID, {
        departmentId: "dept-1",
        dateFrom: "",
        dateTo: "",
        minSamples: "",
      }),
    );
  });

  it("exports CSV through the API with the filters in force (Requirement 21.5)", async () => {
    renderReport();
    await screen.findByRole("heading", { name: "Expense Approval" });

    await userEvent.click(screen.getByRole("button", { name: "Export CSV" }));

    await waitFor(() =>
      expect(mockedReportsApi.exportPerformanceCsv).toHaveBeenCalledWith(WORKFLOW_ID, {
        departmentId: "",
        dateFrom: "",
        dateTo: "",
        minSamples: "",
      }),
    );
  });

  it("exports JSON from the report already on screen (Requirement 21.5)", async () => {
    renderReport();
    await screen.findByRole("heading", { name: "Expense Approval" });

    await userEvent.click(screen.getByRole("button", { name: "Export JSON" }));

    expect(mockedReportsApi.exportPerformanceJson).toHaveBeenCalledWith(report());
  });

  it("surfaces a failed export without discarding the report", async () => {
    mockedReportsApi.exportPerformanceCsv.mockRejectedValue(new Error("Network down"));

    renderReport();
    await screen.findByRole("heading", { name: "Expense Approval" });
    await userEvent.click(screen.getByRole("button", { name: "Export CSV" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Could not export this report as CSV.",
    );
    expect(screen.getByText("25.0%")).toBeInTheDocument();
  });

  it("refuses an EMPLOYEE who reached the page by URL, without calling the endpoint", async () => {
    renderReport("EMPLOYEE");

    expect(await screen.findByRole("heading", { name: "Not authorized" })).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Only administrators and managers can view workflow analytics.",
    );
    expect(mockedReportsApi.fetchWorkflowPerformance).not.toHaveBeenCalled();
  });

  it("shows the same not-authorized state when the API answers 403", async () => {
    mockedReportsApi.fetchWorkflowPerformance.mockRejectedValue(forbidden());

    renderReport("MANAGER");

    expect(await screen.findByRole("heading", { name: "Not authorized" })).toBeInTheDocument();
    expect(screen.queryByText("Could not load this report.")).not.toBeInTheDocument();
  });
});
