import type { Edge, Node } from "@xyflow/react";
import type { SaveDraftInput } from "@/lib/workflowsApi";
import type { NodeType, WorkflowVersion } from "@/types";

/** The single custom node renderer; every FlowForge node is drawn by it, keyed by its own type. */
export const BUILDER_NODE_TYPE = "flowforgeNode";

export type BuilderNodeData = {
  /** The FlowForge node type. Named `nodeType` because `type` on a React Flow node is the renderer. */
  nodeType: NodeType;
  /** The node's `config_json`, exactly as it will be sent and as it was received. */
  config: Record<string, unknown>;
};

export type BuilderNode = Node<BuilderNodeData, typeof BUILDER_NODE_TYPE>;

/** What a canvas edge carries: the optional SpEL condition (Requirements 6.2, 6.3). */
export type BuilderEdgeData = {
  conditionExpr: string | null;
};

export type BuilderEdge = Edge<BuilderEdgeData>;

export interface BuilderGraph {
  nodes: BuilderNode[];
  edges: BuilderEdge[];
}

/** Palette entry: one placeable node type, with the copy the sidebar shows. */
export interface PaletteEntry {
  type: NodeType;
  label: string;
  description: string;
}

export const NODE_PALETTE: readonly PaletteEntry[] = [
  { type: "START", label: "Start", description: "Entry point. Exactly one is required." },
  { type: "TASK", label: "Task", description: "Work assigned to a user or role." },
  { type: "APPROVAL", label: "Approval", description: "Approve or reject decision." },
  { type: "CONDITION", label: "Condition", description: "Routes on its outgoing edge conditions." },
  { type: "NOTIFICATION", label: "Notification", description: "Notifies recipients and continues." },
  { type: "AND_JOIN", label: "AND join", description: "Waits for every parallel branch." },
  { type: "END", label: "End", description: "Terminal step. At least one is required." },
];

export const NODE_TYPE_LABELS: Record<NodeType, string> = {
  START: "Start",
  TASK: "Task",
  APPROVAL: "Approval",
  CONDITION: "Condition",
  NOTIFICATION: "Notification",
  AND_JOIN: "AND join",
  END: "End",
};

/** Node types with no configuration of their own; the backend declares no rule for them. */
const UNCONFIGURED_TYPES: readonly NodeType[] = ["START", "END", "AND_JOIN"];

/** True when this node type has settings the properties panel can edit. */
export function hasConfigurableFields(type: NodeType): boolean {
  return !UNCONFIGURED_TYPES.includes(type);
}

/** True when a node of this type accepts incoming edges. */
export function acceptsIncoming(type: NodeType): boolean {
  return type !== "START";
}

/** True when a node of this type can have outgoing edges. */
export function allowsOutgoing(type: NodeType): boolean {
  return type !== "END";
}

export function newElementId(): string {
  const webCrypto = globalThis.crypto;
  if (webCrypto && typeof webCrypto.randomUUID === "function") {
    return webCrypto.randomUUID();
  }

  const bytes = new Uint8Array(16);
  if (webCrypto && typeof webCrypto.getRandomValues === "function") {
    webCrypto.getRandomValues(bytes);
  } else {
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Math.floor(Math.random() * 256);
    }
  }
  // Version 4, variant 1, per RFC 4122.
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;

  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20),
  ].join("-");
}

/** A new canvas node of the given type at the given position. */
export function createBuilderNode(
  type: NodeType,
  position: { x: number; y: number },
  config: Record<string, unknown> = {},
): BuilderNode {
  return {
    id: newElementId(),
    type: BUILDER_NODE_TYPE,
    position: { x: Math.round(position.x), y: Math.round(position.y) },
    data: { nodeType: type, config },
  };
}

/** A new canvas edge between two nodes, optionally carrying a condition expression. */
export function createBuilderEdge(
  source: string,
  target: string,
  conditionExpr: string | null = null,
): BuilderEdge {
  return {
    id: newElementId(),
    source,
    target,
    label: conditionExpr ?? undefined,
    data: { conditionExpr },
  };
}

export function builderGraphFromVersion(version: WorkflowVersion): BuilderGraph {
  const nodes = (version.nodes ?? []).map<BuilderNode>((node) => ({
    id: node.id,
    type: BUILDER_NODE_TYPE,
    position: { x: node.positionX ?? 0, y: node.positionY ?? 0 },
    data: { nodeType: node.type, config: { ...(node.configJson ?? {}) } },
  }));

  const nodeIds = new Set(nodes.map((node) => node.id));
  const edges = (version.edges ?? [])
    // An edge whose endpoint is absent cannot be drawn; publishing reports it as an orphan (rule 3).
    .filter((edge) => nodeIds.has(edge.sourceNodeId) && nodeIds.has(edge.targetNodeId))
    .map<BuilderEdge>((edge) => ({
      id: edge.id,
      source: edge.sourceNodeId,
      target: edge.targetNodeId,
      label: edge.conditionExpr ?? undefined,
      data: { conditionExpr: edge.conditionExpr },
    }));

  return { nodes, edges };
}

export function toSaveDraftPayload(graph: BuilderGraph): SaveDraftInput {
  return {
    nodes: graph.nodes.map((node) => ({
      id: node.id,
      type: node.data.nodeType,
      configJson: node.data.config ?? {},
      positionX: Math.round(node.position.x),
      positionY: Math.round(node.position.y),
    })),
    edges: graph.edges.map((edge) => ({
      // The server assigns edge ids too; sending ours would claim a primary key we do not own.
      id: null,
      sourceNodeId: edge.source,
      targetNodeId: edge.target,
      conditionExpr: edge.data?.conditionExpr ?? null,
    })),
  };
}

export function remapNodeId(
  previous: readonly BuilderNode[],
  saved: readonly BuilderNode[],
  nodeId: string | null,
): string | null {
  if (!nodeId) {
    return null;
  }
  const index = previous.findIndex((node) => node.id === nodeId);
  if (index < 0 || index >= saved.length) {
    return null;
  }
  return saved[index].id;
}

/** Matches the node id the backend embeds in a publish violation, e.g. "Node <uuid> (APPROVAL) …". */
const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

export function violationNodeId(violation: string): string | null {
  return violation.match(UUID_PATTERN)?.[0] ?? null;
}

/** Short, stable description of an edge for lists and buttons: "Start → Approval". */
export function describeEdge(graph: BuilderGraph, edge: BuilderEdge): string {
  const label = (nodeId: string) => {
    const node = graph.nodes.find((candidate) => candidate.id === nodeId);
    return node ? NODE_TYPE_LABELS[node.data.nodeType] : "unknown node";
  };
  return `${label(edge.source)} → ${label(edge.target)}`;
}

/** The outgoing edges of a node, in authored order — the order the engine evaluates them in. */
export function outgoingEdges(graph: BuilderGraph, nodeId: string): BuilderEdge[] {
  return graph.edges.filter((edge) => edge.source === nodeId);
}
