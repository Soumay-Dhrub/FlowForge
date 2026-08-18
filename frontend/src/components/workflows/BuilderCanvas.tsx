"use client";

/**
 * The React Flow canvas.
 *
 * Split out from {@link ./WorkflowBuilder WorkflowBuilder} for one concrete reason: turning a drop
 * point into graph coordinates needs `useReactFlow`, which only works inside a `ReactFlowProvider`.
 * Keeping the provider and this component together means the builder above can own all the state
 * without knowing anything about viewports.
 *
 * The canvas holds no state of its own. Every change is reported upward, so the graph the save button
 * sends is the same object the canvas is drawing.
 */
import { useCallback, useMemo, useRef } from "react";
import {
  Background,
  Controls,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type Connection,
  type EdgeChange,
  type NodeChange,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import BuilderNode from "@/components/workflows/BuilderNode";
import { NODE_TYPE_MIME } from "@/components/workflows/NodePalette";
import {
  BUILDER_NODE_TYPE,
  type BuilderEdge,
  type BuilderGraph,
  type BuilderNode as BuilderNodeType,
} from "@/lib/workflowGraph";
import type { NodeType } from "@/types";

export interface BuilderCanvasProps {
  graph: BuilderGraph;
  onNodesChange: (changes: NodeChange<BuilderNodeType>[]) => void;
  onEdgesChange: (changes: EdgeChange<BuilderEdge>[]) => void;
  onConnect: (connection: Connection) => void;
  /** A node dropped from the palette, already in graph coordinates. */
  onDropNode: (type: NodeType, position: { x: number; y: number }) => void;
  onSelectNode: (nodeId: string | null) => void;
  onEditEdge: (edge: BuilderEdge) => void;
}

function Canvas({
  graph,
  onNodesChange,
  onEdgesChange,
  onConnect,
  onDropNode,
  onSelectNode,
  onEditEdge,
}: BuilderCanvasProps) {
  const wrapper = useRef<HTMLDivElement>(null);
  const { screenToFlowPosition } = useReactFlow();
  const nodeTypes = useMemo(() => ({ [BUILDER_NODE_TYPE]: BuilderNode }), []);

  const onDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault();
      const type = event.dataTransfer.getData(NODE_TYPE_MIME) as NodeType | "";
      if (!type) {
        return;
      }
      onDropNode(
        type,
        screenToFlowPosition({ x: event.clientX, y: event.clientY }),
      );
    },
    [onDropNode, screenToFlowPosition],
  );

  return (
    <div
      ref={wrapper}
      role="group"
      aria-label="Workflow canvas"
      className="h-[32rem] w-full rounded-xl border border-gray-200 bg-white"
      onDrop={onDrop}
      onDragOver={(event) => {
        event.preventDefault();
        event.dataTransfer.dropEffect = "move";
      }}
    >
      <ReactFlow<BuilderNodeType, BuilderEdge>
        nodes={graph.nodes}
        edges={graph.edges}
        nodeTypes={nodeTypes}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={onConnect}
        onNodeClick={(_event, node) => onSelectNode(node.id)}
        onEdgeClick={(_event, edge) => onEditEdge(edge)}
        onPaneClick={() => onSelectNode(null)}
        fitView
        proOptions={{ hideAttribution: false }}
      >
        <Background />
        <Controls />
      </ReactFlow>
    </div>
  );
}

/** The canvas with its provider; import this rather than the inner component. */
export function BuilderCanvas(props: BuilderCanvasProps) {
  return (
    <ReactFlowProvider>
      <Canvas {...props} />
    </ReactFlowProvider>
  );
}

export default BuilderCanvas;
