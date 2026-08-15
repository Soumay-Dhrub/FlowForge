import {
  BUILDER_NODE_TYPE,
  builderGraphFromVersion,
  createBuilderEdge,
  createBuilderNode,
  newElementId,
  remapNodeId,
  toSaveDraftPayload,
  violationNodeId,
  type BuilderGraph,
} from "@/lib/workflowGraph";
import type { WorkflowVersion } from "@/types";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function version(overrides: Partial<WorkflowVersion> = {}): WorkflowVersion {
  return {
    id: "11111111-1111-1111-1111-111111111111",
    workflowId: "22222222-2222-2222-2222-222222222222",
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

describe("workflowGraph", () => {
  it("mints node ids the backend will accept as UUIDs", () => {
    expect(newElementId()).toMatch(UUID);
    expect(newElementId()).not.toEqual(newElementId());
  });

  it("serialises a canvas into the draft-save payload shape", () => {
    const start = createBuilderNode("START", { x: 10.4, y: 20.6 });
    const approval = createBuilderNode("APPROVAL", { x: 200, y: 20 }, { approverRole: "MANAGER" });
    const graph: BuilderGraph = {
      nodes: [start, approval],
      edges: [createBuilderEdge(start.id, approval.id)],
    };

    expect(toSaveDraftPayload(graph)).toEqual({
      nodes: [
        { id: start.id, type: "START", configJson: {}, positionX: 10, positionY: 21 },
        {
          id: approval.id,
          type: "APPROVAL",
          configJson: { approverRole: "MANAGER" },
          positionX: 200,
          positionY: 20,
        },
      ],
      edges: [
        // Edge ids belong to the server; the endpoints name nodes in this same payload.
        { id: null, sourceNodeId: start.id, targetNodeId: approval.id, conditionExpr: null },
      ],
    });
  });

  it("keeps the authored order of edges, which is the order conditions are evaluated in", () => {
    const condition = createBuilderNode("CONDITION", { x: 0, y: 0 });
    const high = createBuilderNode("TASK", { x: 100, y: 0 });
    const low = createBuilderNode("TASK", { x: 100, y: 100 });
    const graph: BuilderGraph = {
      nodes: [condition, high, low],
      edges: [
        createBuilderEdge(condition.id, high.id, "amount > 1000"),
        createBuilderEdge(condition.id, low.id, null),
      ],
    };

    expect(toSaveDraftPayload(graph).edges.map((edge) => edge.conditionExpr)).toEqual([
      "amount > 1000",
      null,
    ]);
  });

  it("re-seeds the canvas from a save response, adopting the server's node ids", () => {
    const start = createBuilderNode("START", { x: 0, y: 0 });
    const end = createBuilderNode("END", { x: 200, y: 0 });
    const sent: BuilderGraph = { nodes: [start, end], edges: [createBuilderEdge(start.id, end.id)] };

    // What the server actually answers: the same graph, with identifiers of its own choosing.
    const saved = version({
      nodes: [
        {
          id: "aaaaaaaa-0000-4000-8000-000000000001",
          versionId: "11111111-1111-1111-1111-111111111111",
          type: "START",
          configJson: {},
          positionX: 0,
          positionY: 0,
        },
        {
          id: "aaaaaaaa-0000-4000-8000-000000000002",
          versionId: "11111111-1111-1111-1111-111111111111",
          type: "END",
          configJson: {},
          positionX: 200,
          positionY: 0,
        },
      ],
      edges: [
        {
          id: "bbbbbbbb-0000-4000-8000-000000000001",
          versionId: "11111111-1111-1111-1111-111111111111",
          sourceNodeId: "aaaaaaaa-0000-4000-8000-000000000001",
          targetNodeId: "aaaaaaaa-0000-4000-8000-000000000002",
          conditionExpr: null,
        },
      ],
    });

    const reseeded = builderGraphFromVersion(saved);

    expect(reseeded.nodes.map((node) => node.id)).toEqual([
      "aaaaaaaa-0000-4000-8000-000000000001",
      "aaaaaaaa-0000-4000-8000-000000000002",
    ]);
    expect(reseeded.nodes.map((node) => node.id)).not.toEqual(sent.nodes.map((node) => node.id));
    expect(reseeded.nodes.every((node) => node.type === BUILDER_NODE_TYPE)).toBe(true);

    // The point of re-seeding: a second save names nodes that exist in its own payload.
    const secondSave = toSaveDraftPayload(reseeded);
    const payloadNodeIds = secondSave.nodes.map((node) => node.id);
    secondSave.edges.forEach((edge) => {
      expect(payloadNodeIds).toContain(edge.sourceNodeId);
      expect(payloadNodeIds).toContain(edge.targetNodeId);
    });
  });

  it("drops an edge whose endpoint is missing from the version rather than drawing a dangling one", () => {
    const graph = builderGraphFromVersion(
      version({
        nodes: [
          {
            id: "aaaaaaaa-0000-4000-8000-000000000001",
            versionId: "11111111-1111-1111-1111-111111111111",
            type: "START",
            configJson: null,
            positionX: null,
            positionY: null,
          },
        ],
        edges: [
          {
            id: "bbbbbbbb-0000-4000-8000-000000000001",
            versionId: "11111111-1111-1111-1111-111111111111",
            sourceNodeId: "aaaaaaaa-0000-4000-8000-000000000001",
            targetNodeId: "cccccccc-0000-4000-8000-000000000009",
            conditionExpr: null,
          },
        ],
      }),
    );

    expect(graph.nodes).toHaveLength(1);
    expect(graph.nodes[0].position).toEqual({ x: 0, y: 0 });
    expect(graph.edges).toHaveLength(0);
  });

  it("carries the selected node across a save by its position in the payload", () => {
    const previous = [
      createBuilderNode("START", { x: 0, y: 0 }),
      createBuilderNode("END", { x: 100, y: 0 }),
    ];
    const saved = [
      { ...previous[0], id: "aaaaaaaa-0000-4000-8000-000000000001" },
      { ...previous[1], id: "aaaaaaaa-0000-4000-8000-000000000002" },
    ];

    expect(remapNodeId(previous, saved, previous[1].id)).toBe(
      "aaaaaaaa-0000-4000-8000-000000000002",
    );
    expect(remapNodeId(previous, saved, null)).toBeNull();
    expect(remapNodeId(previous, saved, "not-on-the-canvas")).toBeNull();
  });

  it("pulls the node id out of a publish violation that names one", () => {
    expect(
      violationNodeId(
        "Node aaaaaaaa-0000-4000-8000-000000000002 (APPROVAL) configures no approver: set 'approverUserId' to a user id or 'approverRole' to a role name",
      ),
    ).toBe("aaaaaaaa-0000-4000-8000-000000000002");
    expect(violationNodeId("Graph must contain exactly one Start node, found 0")).toBeNull();
  });
});
