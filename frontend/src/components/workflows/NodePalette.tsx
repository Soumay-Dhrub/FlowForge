"use client";

import { GripVertical } from "lucide-react";
import { NODE_PALETTE } from "@/lib/workflowGraph";
import type { NodeType } from "@/types";

/** The drag payload's MIME type, shared with the canvas' drop handler. */
export const NODE_TYPE_MIME = "application/flowforge-node-type";

export function NodePalette({
  onAddNode,
  disabled = false,
}: {
  /** Place a node of this type; the canvas decides where when there was no drop point. */
  onAddNode: (type: NodeType) => void;
  disabled?: boolean;
}) {
  return (
    <section aria-labelledby="node-palette-heading">
      <h2 id="node-palette-heading" className="text-sm font-semibold text-gray-900">
        Nodes
      </h2>
      <p className="mt-1 text-xs text-gray-600">
        Drag onto the canvas, or select one to add it.
      </p>
      <ul className="mt-3 space-y-2">
        {NODE_PALETTE.map((entry) => (
          <li key={entry.type}>
            <button
              type="button"
              draggable={!disabled}
              disabled={disabled}
              onDragStart={(event) => {
                event.dataTransfer.setData(NODE_TYPE_MIME, entry.type);
                event.dataTransfer.effectAllowed = "move";
              }}
              onClick={() => onAddNode(entry.type)}
              className="flex w-full items-start gap-2 rounded-md border border-gray-300 bg-white px-2.5 py-2 text-left hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-60"
            >
              <GripVertical aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0 text-gray-400" />
              <span>
                <span className="block text-sm font-medium text-gray-900">Add {entry.label}</span>
                <span className="block text-xs text-gray-600">{entry.description}</span>
              </span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}

export default NodePalette;
