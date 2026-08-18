import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import WorkflowVersionHistory from "@/components/workflows/WorkflowVersionHistory";
import { AuthProvider } from "@/context/AuthContext";
import * as authApi from "@/lib/authApi";
import { setTokens } from "@/lib/tokenStorage";
import * as workflowsApi from "@/lib/workflowsApi";
import type { RoleName, User, Workflow, WorkflowVersion } from "@/types";
import { createTestQueryClient } from "@/test/renderWithQuery";

jest.mock("@/lib/authApi");
jest.mock("@/lib/workflowsApi", () => ({
  ...jest.requireActual("@/lib/workflowsApi"),
  fetchWorkflow: jest.fn(),
  publishVersion: jest.fn(),
  cloneWorkflow: jest.fn(),
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
const mockedAuthApi = jest.mocked(authApi);

function userWithRole(roleName: RoleName): User {
  return {
    id: "22222222-2222-2222-2222-222222222222",
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

function version(overrides: Partial<WorkflowVersion> = {}): WorkflowVersion {
  return {
    id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    workflowId: "11111111-1111-1111-1111-111111111111",
    versionNumber: 1,
    graphJson: null,
    isPublished: true,
    isCurrent: true,
    publishedAt: "2026-01-03T09:30:00Z",
    publishedById: "22222222-2222-2222-2222-222222222222",
    publishedByName: "Ada Lovelace",
    createdAt: "2026-01-01T10:00:00Z",
    updatedAt: "2026-01-03T09:30:00Z",
    nodes: [],
    edges: [],
    ...overrides,
  };
}

function workflow(versions: WorkflowVersion[]): Workflow {
  return {
    id: "11111111-1111-1111-1111-111111111111",
    name: "Expense approval",
    description: null,
    status: "ACTIVE",
    createdById: "22222222-2222-2222-2222-222222222222",
    createdByName: "Ada Lovelace",
    createdAt: "2026-01-01T10:00:00Z",
    updatedAt: "2026-01-03T09:30:00Z",
    versions,
  };
}

function renderHistory(role: RoleName) {
  setTokens({
    accessToken: "access-1",
    refreshToken: "refresh-1",
    tokenType: "Bearer",
    expiresIn: 900,
  });
  mockedAuthApi.fetchCurrentUser.mockResolvedValue(userWithRole(role));

  const queryClient = createTestQueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <WorkflowVersionHistory workflowId="11111111-1111-1111-1111-111111111111" />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

describe("WorkflowVersionHistory", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    window.localStorage.clear();
  });

  it("lists versions newest first with their publish timestamp and author", async () => {
    mockedApi.fetchWorkflow.mockResolvedValue(
      workflow([
        version(),
        version({
          id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
          versionNumber: 2,
          isPublished: false,
          isCurrent: false,
          publishedAt: null,
          publishedByName: null,
        }),
      ]),
    );

    renderHistory("ADMIN");

    const table = await screen.findByRole("table");
    const rowHeaders = within(table).getAllByRole("rowheader").map((cell) => cell.textContent);
    expect(rowHeaders).toEqual(["v2", "v1"]);

    // Header row first, then v2 (the open draft) and v1 (published, with its author).
    const [, draftRow, publishedRow] = within(table).getAllByRole("row");
    expect(draftRow).toHaveTextContent("Draft");
    expect(publishedRow).toHaveTextContent("Published");
    expect(publishedRow).toHaveTextContent("Current");
    expect(publishedRow).toHaveTextContent("Ada Lovelace");
  });

  it("does not offer publishing to a manager, who would only be refused", async () => {
    mockedApi.fetchWorkflow.mockResolvedValue(
      workflow([version({ isPublished: false, isCurrent: false, publishedAt: null })]),
    );

    renderHistory("MANAGER");

    await screen.findByRole("table");
    expect(screen.queryByRole("button", { name: /Publish version/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Clone version 1/ })).toBeInTheDocument();
  });

  it("lists every structural violation when a publish is refused", async () => {
    mockedApi.fetchWorkflow.mockResolvedValue(
      workflow([version({ isPublished: false, isCurrent: false, publishedAt: null })]),
    );
    mockedApi.publishVersion.mockRejectedValue(
      new workflowsApi.WorkflowPublishError("Workflow graph is invalid", [
        "The graph must contain exactly one Start node",
        "The graph must contain at least one End node",
      ]),
    );

    renderHistory("ADMIN");
    await userEvent.click(await screen.findByRole("button", { name: /Publish version 1/ }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Workflow graph is invalid");
    expect(within(alert).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      "The graph must contain exactly one Start node",
      "The graph must contain at least one End node",
    ]);
  });

  it("expands a version's stored graph in place", async () => {
    mockedApi.fetchWorkflow.mockResolvedValue(
      workflow([
        version({
          nodes: [
            {
              id: "n1",
              versionId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              type: "START",
              configJson: null,
              positionX: 0,
              positionY: 0,
            },
            {
              id: "n2",
              versionId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              type: "END",
              configJson: null,
              positionX: 1,
              positionY: 0,
            },
          ],
          edges: [
            {
              id: "e1",
              versionId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              sourceNodeId: "n1",
              targetNodeId: "n2",
              conditionExpr: null,
            },
          ],
        }),
      ]),
    );

    renderHistory("ADMIN");
    const view = await screen.findByRole("button", { name: /View version 1/ });
    expect(view).toHaveAttribute("aria-expanded", "false");

    await userEvent.click(view);

    await waitFor(() => expect(view).toHaveAttribute("aria-expanded", "true"));
    expect(screen.getByText("Nodes (2)")).toBeInTheDocument();
    expect(screen.getByText("START → END")).toBeInTheDocument();
  });
});
