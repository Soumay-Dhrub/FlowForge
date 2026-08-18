import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AxiosError, AxiosHeaders } from "axios";
import WorkflowList from "@/components/workflows/WorkflowList";
import * as workflowsApi from "@/lib/workflowsApi";
import type { Workflow } from "@/types";
import { createTestQueryClient } from "@/test/renderWithQuery";

jest.mock("@/lib/workflowsApi", () => ({
  ...jest.requireActual("@/lib/workflowsApi"),
  fetchWorkflows: jest.fn(),
  cloneWorkflow: jest.fn(),
  createWorkflow: jest.fn(),
}));

jest.mock("next/link", () => ({
  __esModule: true,
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const mockedApi = jest.mocked(workflowsApi);

function workflow(overrides: Partial<Workflow> = {}): Workflow {
  return {
    id: "11111111-1111-1111-1111-111111111111",
    name: "Expense approval",
    description: "Reimbursements over £50",
    status: "ACTIVE",
    createdById: "22222222-2222-2222-2222-222222222222",
    createdByName: "Ada Lovelace",
    createdAt: "2026-01-01T10:00:00Z",
    updatedAt: "2026-01-02T10:00:00Z",
    versions: null,
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

function renderList() {
  const queryClient = createTestQueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <WorkflowList />
    </QueryClientProvider>,
  );
}

describe("WorkflowList", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockedApi.fetchWorkflows.mockResolvedValue([]);
  });

  it("renders each workflow as a row in a real table with column headers", async () => {
    mockedApi.fetchWorkflows.mockResolvedValue([
      workflow(),
      workflow({
        id: "33333333-3333-3333-3333-333333333333",
        name: "Leave request",
        status: "DRAFT",
        createdByName: "Grace Hopper",
      }),
    ]);

    renderList();

    const table = await screen.findByRole("table");
    ["Name", "Status", "Created by", "Last updated", "Actions"].forEach((header) => {
      expect(within(table).getByRole("columnheader", { name: header })).toBeInTheDocument();
    });
    expect(within(table).getByRole("rowheader", { name: /Expense approval/ })).toBeInTheDocument();
    expect(within(table).getByRole("rowheader", { name: /Leave request/ })).toBeInTheDocument();
    expect(within(table).getByText("Active")).toBeInTheDocument();
    expect(within(table).getByText("Draft")).toBeInTheDocument();
    expect(within(table).getByText("Ada Lovelace")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Expense approval" })).toHaveAttribute(
      "href",
      "/workflows/11111111-1111-1111-1111-111111111111",
    );
  });

  it("filters through the backend's name parameter rather than in the browser", async () => {
    mockedApi.fetchWorkflows.mockResolvedValue([workflow()]);

    renderList();
    await screen.findByRole("rowheader", { name: /Expense approval/ });

    mockedApi.fetchWorkflows.mockResolvedValue([
      workflow({ id: "44444444-4444-4444-4444-444444444444", name: "Leave request" }),
    ]);
    await userEvent.type(screen.getByLabelText("Search by name"), "leave");

    await waitFor(() => expect(mockedApi.fetchWorkflows).toHaveBeenLastCalledWith("leave"));
    expect(await screen.findByRole("rowheader", { name: /Leave request/ })).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByRole("rowheader", { name: /Expense approval/ })).not.toBeInTheDocument(),
    );
  });

  it("distinguishes an empty result for a search from an empty workflow list", async () => {
    renderList();

    expect(
      await screen.findByText("No workflows yet. Create one to get started."),
    ).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText("Search by name"), "payroll");

    expect(await screen.findByText("No workflows match “payroll”.")).toBeInTheDocument();
  });

  it("reports a failed request as an error, not as an empty list", async () => {
    mockedApi.fetchWorkflows.mockRejectedValue(new Error("Network down"));

    renderList();

    expect(await screen.findByRole("alert")).toHaveTextContent("Could not load workflows.");
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
    expect(screen.queryByText("No workflows yet. Create one to get started.")).not.toBeInTheDocument();
  });

  it("shows a not-authorized state when the caller's role may not read workflows", async () => {
    mockedApi.fetchWorkflows.mockRejectedValue(forbidden());

    renderList();

    expect(await screen.findByRole("heading", { name: "Not authorized" })).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("clones a workflow and reports the copy it created", async () => {
    mockedApi.fetchWorkflows.mockResolvedValue([workflow()]);
    mockedApi.cloneWorkflow.mockResolvedValue(
      workflow({ id: "55555555-5555-5555-5555-555555555555", name: "Expense approval (copy)" }),
    );

    renderList();
    await userEvent.click(await screen.findByRole("button", { name: /Clone Expense approval/ }));

    await waitFor(() =>
      expect(mockedApi.cloneWorkflow).toHaveBeenCalledWith("11111111-1111-1111-1111-111111111111"),
    );
    expect(await screen.findByRole("status")).toHaveTextContent(
      "Cloned into “Expense approval (copy)”.",
    );
  });
});
