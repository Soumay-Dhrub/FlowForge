import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import WorkflowBuilder from "@/components/workflows/WorkflowBuilder";
import { AuthProvider } from "@/context/AuthContext";
import * as authApi from "@/lib/authApi";
import { setTokens } from "@/lib/tokenStorage";
import * as workflowsApi from "@/lib/workflowsApi";
import type { RoleName, User, Workflow, WorkflowVersion } from "@/types";
import { createTestQueryClient } from "@/test/renderWithQuery";

jest.mock("@xyflow/react", () => {
  const react = jest.requireActual("react");
  type StubNode = { id: string; data: { nodeType: string } };
  type StubEdge = { id: string; source: string; target: string };
  return {
    __esModule: true,
    ReactFlow: ({
      nodes,
      onConnect,
      children,
    }: {
      nodes: StubNode[];
      onConnect: (connection: {
        source: string;
        target: string;
        sourceHandle: null;
        targetHandle: null;
      }) => void;
      children?: React.ReactNode;
    }) =>
      react.createElement(
        "div",
        { "data-testid": "canvas" },
        react.createElement(
          "button",
          {
            type: "button",
            onClick: () =>
              onConnect({
                source: nodes[0]?.id,
                target: nodes[1]?.id,
                sourceHandle: null,
                targetHandle: null,
              }),
          },
          "connect the first two nodes",
        ),
        react.createElement(
          "ul",
          null,
          nodes.map((node: StubNode) =>
            react.createElement("li", { key: node.id }, `canvas node ${node.data.nodeType}`),
          ),
        ),
        children,
      ),
    ReactFlowProvider: ({ children }: { children: React.ReactNode }) =>
      react.createElement(react.Fragment, null, children),
    useReactFlow: () => ({ screenToFlowPosition: (point: { x: number; y: number }) => point }),
    Background: () => null,
    Controls: () => null,
    Handle: () => null,
    Position: { Left: "left", Right: "right" },
    applyNodeChanges: (_changes: unknown, nodes: StubNode[]) => nodes,
    applyEdgeChanges: (_changes: unknown, edges: StubEdge[]) => edges,
  };
});

jest.mock("@/lib/authApi");
jest.mock("@/lib/workflowsApi", () => ({
  ...jest.requireActual("@/lib/workflowsApi"),
  fetchWorkflow: jest.fn(),
  saveDraft: jest.fn(),
  publishVersion: jest.fn(),
}));
jest.mock("@/lib/referenceDataApi", () => ({
  ...jest.requireActual("@/lib/referenceDataApi"),
  fetchRoles: jest.fn().mockResolvedValue([{ id: "role-1", name: "MANAGER" }]),
}));
jest.mock("@/lib/usersApi", () => ({
  ...jest.requireActual("@/lib/usersApi"),
  fetchUsers: jest.fn().mockResolvedValue([]),
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

const WORKFLOW_ID = "11111111-1111-1111-1111-111111111111";
const DRAFT_ID = "33333333-3333-3333-3333-333333333333";
const SERVER_START_ID = "aaaaaaaa-0000-4000-8000-000000000001";
const SERVER_APPROVAL_ID = "aaaaaaaa-0000-4000-8000-000000000002";

function adminUser(roleName: RoleName = "ADMIN"): User {
  return {
    id: "22222222-2222-2222-2222-222222222222",
    name: "Ada Lovelace",
    email: "ada.lovelace@flowforge.local",
    roleId: "role-1",
    roleName,
    departmentId: null,
    departmentName: null,
    isActive: true,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
  };
}

function draftVersion(overrides: Partial<WorkflowVersion> = {}): WorkflowVersion {
  return {
    id: DRAFT_ID,
    workflowId: WORKFLOW_ID,
    versionNumber: 1,
    graphJson: null,
    isPublished: false,
    isCurrent: false,
    publishedAt: null,
    publishedById: null,
    publishedByName: null,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    nodes: [],
    edges: [],
    ...overrides,
  };
}

function workflow(versions: WorkflowVersion[] = [draftVersion()]): Workflow {
  return {
    id: WORKFLOW_ID,
    name: "Expense approval",
    description: null,
    status: "DRAFT",
    createdById: "22222222-2222-2222-2222-222222222222",
    createdByName: "Ada Lovelace",
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    versions,
  };
}

/** The version a save of Start → Approval comes back as: same graph, server-assigned identifiers. */
function savedStartToApproval(): WorkflowVersion {
  return draftVersion({
    nodes: [
      {
        id: SERVER_START_ID,
        versionId: DRAFT_ID,
        type: "START",
        configJson: {},
        positionX: 60,
        positionY: 60,
      },
      {
        id: SERVER_APPROVAL_ID,
        versionId: DRAFT_ID,
        type: "APPROVAL",
        configJson: {},
        positionX: 250,
        positionY: 60,
      },
    ],
    edges: [
      {
        id: "bbbbbbbb-0000-4000-8000-000000000001",
        versionId: DRAFT_ID,
        sourceNodeId: SERVER_START_ID,
        targetNodeId: SERVER_APPROVAL_ID,
        conditionExpr: null,
      },
    ],
  });
}

function renderBuilder(roleName: RoleName = "ADMIN") {
  setTokens({
    accessToken: "access-1",
    refreshToken: "refresh-1",
    tokenType: "Bearer",
    expiresIn: 900,
  });
  mockedAuthApi.fetchCurrentUser.mockResolvedValue(adminUser(roleName));

  const queryClient = createTestQueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <WorkflowBuilder workflowId={WORKFLOW_ID} />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

/** Place Start then Approval and connect them, using only the keyboard-reachable controls. */
async function drawStartToApproval() {
  await userEvent.click(await screen.findByRole("button", { name: /Add Start/ }));
  await userEvent.click(screen.getByRole("button", { name: /Add Approval/ }));
  await userEvent.click(screen.getByRole("button", { name: "connect the first two nodes" }));
}

describe("WorkflowBuilder", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    window.localStorage.clear();
    mockedApi.fetchWorkflow.mockResolvedValue(workflow());
  });

  it("adds a node from the palette without a drag", async () => {
    renderBuilder();

    await userEvent.click(await screen.findByRole("button", { name: /Add Approval/ }));

    expect(screen.getByText("canvas node APPROVAL")).toBeInTheDocument();
    // Adding selects it, so its settings — and its required approver — are immediately reachable.
    expect(screen.getByRole("heading", { name: "Approval settings" })).toBeInTheDocument();
    expect(screen.getByLabelText("Approver role")).toBeInTheDocument();
  });

  it("connects two nodes into a directed edge", async () => {
    renderBuilder();
    await drawStartToApproval();

    const edges = screen.getByRole("region", { name: "Edges" });
    expect(within(edges).getByText("Start → Approval")).toBeInTheDocument();
  });

  it("saves the draft with a correctly shaped payload and re-seeds from the response", async () => {
    mockedApi.saveDraft.mockResolvedValue(savedStartToApproval());
    renderBuilder();
    await drawStartToApproval();

    await userEvent.click(screen.getByRole("button", { name: /Save draft/ }));

    await waitFor(() => expect(mockedApi.saveDraft).toHaveBeenCalledTimes(1));
    const [workflowArg, versionArg, firstPayload] = mockedApi.saveDraft.mock.calls[0];
    expect(workflowArg).toBe(WORKFLOW_ID);
    expect(versionArg).toBe(DRAFT_ID);
    expect(firstPayload.nodes.map((node) => node.type)).toEqual(["START", "APPROVAL"]);
    expect(firstPayload.nodes[0]).toMatchObject({ configJson: {}, positionX: 60, positionY: 60 });
    expect(firstPayload.edges).toEqual([
      {
        id: null,
        sourceNodeId: firstPayload.nodes[0].id,
        targetNodeId: firstPayload.nodes[1].id,
        conditionExpr: null,
      },
    ]);

    await screen.findByText(/Draft saved: 2 node\(s\), 1 edge\(s\)/);

    // The whole point: a second save must name the ids the server assigned, not the ones we minted.
    await userEvent.click(screen.getByRole("button", { name: /Save draft/ }));
    await waitFor(() => expect(mockedApi.saveDraft).toHaveBeenCalledTimes(2));
    const secondPayload = mockedApi.saveDraft.mock.calls[1][2];
    expect(secondPayload.nodes.map((node) => node.id)).toEqual([
      SERVER_START_ID,
      SERVER_APPROVAL_ID,
    ]);
    expect(secondPayload.edges).toEqual([
      {
        id: null,
        sourceNodeId: SERVER_START_ID,
        targetNodeId: SERVER_APPROVAL_ID,
        conditionExpr: null,
      },
    ]);
  });

  it("lists every publish violation, labelling the ones that name a node", async () => {
    mockedApi.saveDraft.mockResolvedValue(savedStartToApproval());
    mockedApi.publishVersion.mockRejectedValue(
      new workflowsApi.WorkflowPublishError("Workflow graph is invalid", [
        "Graph must contain at least one End node",
        `Node ${SERVER_APPROVAL_ID} (APPROVAL) configures no approver: set 'approverUserId' to a user id or 'approverRole' to a role name`,
      ]),
    );

    renderBuilder();
    await drawStartToApproval();

    // Publishing uses the stored draft, so the canvas has to be saved first.
    await userEvent.click(screen.getByRole("button", { name: /Save draft/ }));
    await screen.findByText(/Draft saved/);

    await userEvent.click(screen.getByRole("button", { name: /Publish/ }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Workflow graph is invalid");
    const violations = within(alert).getAllByRole("listitem").map((item) => item.textContent);
    expect(violations).toHaveLength(2);
    expect(violations[0]).toBe("Graph must contain at least one End node");
    expect(violations[1]).toContain("Approval: ");
    expect(violations[1]).toContain("configures no approver");
  });

  it("does not offer publishing to a manager, who would only be refused", async () => {
    renderBuilder("MANAGER");

    await screen.findByRole("button", { name: /Add Start/ });
    expect(screen.getByRole("button", { name: /Save draft/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Publish/ })).not.toBeInTheDocument();
  });

  it("says so plainly when every version is published and none can be edited", async () => {
    mockedApi.fetchWorkflow.mockResolvedValue(
      workflow([draftVersion({ isPublished: true, isCurrent: true })]),
    );

    renderBuilder();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /Every version of this workflow is published/,
    );
  });
});
