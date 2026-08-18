"use client";

/**
 * The canvas renderer for every FlowForge node type.
 *
 * One component rather than seven: the types differ in their colour, their handles and their
 * configuration, not in their behaviour on the canvas, so a single renderer keyed by `data.nodeType`
 * keeps them visually consistent and leaves one place to change.
 *
 * Handles follow what the graph allows: a Start node has no target handle and an End node no source
 * handle, so the canvas refuses connections the publish rules would only reject later.
 */
import { Handle, Position, type NodeProps } from "@xyflow/react";
import {
  NODE_TYPE_LABELS,
  acceptsIncoming,
  allowsOutgoing,
  type BuilderNode as BuilderNodeType,
} from "@/lib/workflowGraph";
import type { NodeType } from "@/types";

const NODE_STYLES: Record<NodeType, string> = {
  START: "border-green-400 bg-success-50 text-green-900",
  TASK: "border-primary-300 bg-primary-50 text-primary-900",
  APPROVAL: "border-indigo-300 bg-indigo-50 text-indigo-900",
  CONDITION: "border-amber-300 bg-warning-50 text-amber-900",
  NOTIFICATION: "border-sky-300 bg-sky-50 text-sky-900",
  AND_JOIN: "border-purple-300 bg-purple-50 text-purple-900",
  END: "border-gray-400 bg-gray-100 text-gray-900",
};

export function BuilderNode({ data, selected }: NodeProps<BuilderNodeType>) {
  const label = NODE_TYPE_LABELS[data.nodeType];

  return (
    <div
      className={`min-w-[7rem] rounded-md border px-3 py-2 text-center text-xs font-medium shadow-sm ${
        NODE_STYLES[data.nodeType]
      } ${selected ? "ring-2 ring-primary-500 ring-offset-1" : ""}`}
    >
      {acceptsIncoming(data.nodeType) ? (
        <Handle type="target" position={Position.Left} aria-label={`Incoming edges for ${label}`} />
      ) : null}
      {label}
      {allowsOutgoing(data.nodeType) ? (
        <Handle type="source" position={Position.Right} aria-label={`Outgoing edges for ${label}`} />
      ) : null}
    </div>
  );
}

export default BuilderNode;
