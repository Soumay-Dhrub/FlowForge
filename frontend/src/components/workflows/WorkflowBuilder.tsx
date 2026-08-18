"use client";

/**
 * The workflow builder (Requirements 6.1–6.5, 7.1–7.5).
 *
 * ## Which version is edited
 * A published version is immutable and a draft save aimed at one is refused with 409, so the editor
 * targets the newest *unpublished* version. Normally there is exactly one — a workflow is created with
 * a draft, and publishing opens its successor — but the no-draft case is stated plainly rather than
 * silently editing something frozen.
 *
 * ## Node ids, and why the canvas is rebuilt after every save
 * The ids the canvas mints are payload-local correlation keys. The server discards them, assigns its
 * own, and reports those in the response; the edges of a payload resolve against the ids *in that
 * payload* and nothing else. So a second save that reused the first save's ids would name nodes the
 * server has never heard of and be rejected with 422. The canvas therefore adopts the response as its
 * new state after every save — see `builderGraphFromVersion` — and the selected node is carried across
 * by position, because the backend writes and reports nodes in payload order.
 *
 * ## Publishing
 * ADMIN only; a MANAGER is not shown a button that would answer 403. Publishing sends no graph, so it
 * publishes what is *stored* — which is why it is disabled while there are unsaved changes rather than
 * quietly publishing an older canvas. A 422 lists every violation at once, each labelled with the node
 * it names where the message carries an id.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { applyEdgeChanges, applyNodeChanges, type Connection, type EdgeChange, type NodeChange } from "@xyflow/react";
import { Loader2, Save, Upload } from "lucide-react";
import { extractErrorMessage, isForbiddenError, isStatusError } from "@/lib/api";
import {
  WorkflowDraftError,
  WorkflowPublishError,
  editableDraftVersion,
  fetchWorkflow,
  publishVersion,
  saveDraft,
  workflowKeys,
} from "@/lib/workflowsApi";
import {
  NODE_TYPE_LABELS,
  builderGraphFromVersion,
  createBuilderEdge,
  createBuilderNode,
  describeEdge,
  remapNodeId,
  toSaveDraftPayload,
  violationNodeId,
  type BuilderEdge,
  type BuilderGraph,
  type BuilderNode,
} from "@/lib/workflowGraph";
import { useAuth } from "@/context/AuthContext";
import NotAuthorized from "@/components/ui/NotAuthorized";
import SelectField from "@/components/ui/SelectField";
import BuilderCanvas from "@/components/workflows/BuilderCanvas";
import EdgeConditionModal from "@/components/workflows/EdgeConditionModal";
import NodePalette from "@/components/workflows/NodePalette";
import NodePropertiesPanel from "@/components/workflows/NodePropertiesPanel";
import type { NodeType } from "@/types";

const EMPTY_GRAPH: BuilderGraph = { nodes: [], edges: [] };

/** Grid step used when a node is added by click rather than dropped at a point. */
const AUTO_PLACE_STEP_X = 190;
const AUTO_PLACE_STEP_Y = 120;
const AUTO_PLACE_COLUMNS = 4;

/**
 * True when a batch of changes alters what a save would send.
 *
 * Selecting a node or measuring it does not change the graph, so those must not mark the canvas
 * dirty — otherwise simply clicking a node would block publishing.
 */
function changesGraph(changes: { type: string }[]): boolean {
  return changes.some((change) => change.type !== "select" && change.type !== "dimensions");
}

export function WorkflowBuilder({ workflowId }: { workflowId: string }) {
  const { user } = useAuth();
  const queryClient = useQueryClient();

  const workflowQuery = useQuery({
    queryKey: workflowKeys.detail(workflowId),
    queryFn: () => fetchWorkflow(workflowId),
  });

  const draft = useMemo(() => editableDraftVersion(workflowQuery.data), [workflowQuery.data]);

  /** Null until the draft has been loaded and seeded; also reset after a publish. */
  const [graph, setGraph] = useState<BuilderGraph | null>(null);
  const [dirty, setDirty] = useState(false);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [editingEdgeId, setEditingEdgeId] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [publishFailure, setPublishFailure] = useState<WorkflowPublishError | null>(null);
  const [draftFailure, setDraftFailure] = useState<WorkflowDraftError | null>(null);
  const seededVersionId = useRef<string | null>(null);

  // Seed the canvas from the draft exactly once per draft version: re-seeding on every refetch would
  // throw away edits made while a background query was in flight.
  useEffect(() => {
    if (!draft || seededVersionId.current === draft.id) {
      return;
    }
    seededVersionId.current = draft.id;
    setGraph(builderGraphFromVersion(draft));
    setSelectedNodeId(null);
    setDirty(false);
  }, [draft]);

  // Unsaved-changes safety for reloads and closing the tab (Requirement 6.4 is about persistence;
  // this is about not losing the canvas before it happens).
  useEffect(() => {
    if (!dirty) {
      return;
    }
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  const currentGraph = graph ?? EMPTY_GRAPH;

  const updateGraph = useCallback((next: BuilderGraph, markDirty = true) => {
    setGraph(next);
    if (markDirty) {
      setDirty(true);
    }
  }, []);

  const onNodesChange = useCallback(
    (changes: NodeChange<BuilderNode>[]) => {
      setGraph((previous) => {
        const base = previous ?? EMPTY_GRAPH;
        return { ...base, nodes: applyNodeChanges(changes, base.nodes) };
      });
      if (changesGraph(changes)) {
        setDirty(true);
      }
    },
    [],
  );

  const onEdgesChange = useCallback(
    (changes: EdgeChange<BuilderEdge>[]) => {
      setGraph((previous) => {
        const base = previous ?? EMPTY_GRAPH;
        return { ...base, edges: applyEdgeChanges(changes, base.edges) };
      });
      if (changesGraph(changes)) {
        setDirty(true);
      }
    },
    [],
  );

  const onConnect = useCallback(
    (connection: Connection) => {
      if (!connection.source || !connection.target || connection.source === connection.target) {
        return;
      }
      setGraph((previous) => {
        const base = previous ?? EMPTY_GRAPH;
        const alreadyConnected = base.edges.some(
          (edge) => edge.source === connection.source && edge.target === connection.target,
        );
        if (alreadyConnected) {
          return base;
        }
        const edge = createBuilderEdge(connection.source, connection.target);
        const source = base.nodes.find((node) => node.id === connection.source);
        // A Condition node routes by expression, so ask for one as soon as the edge exists rather
        // than leaving a branch that can never be taken (Requirement 6.3).
        if (source?.data.nodeType === "CONDITION") {
          setEditingEdgeId(edge.id);
        }
        return { ...base, edges: [...base.edges, edge] };
      });
      setDirty(true);
    },
    [],
  );

  const addNode = useCallback(
    (type: NodeType, position?: { x: number; y: number }) => {
      setGraph((previous) => {
        const base = previous ?? EMPTY_GRAPH;
        const index = base.nodes.length;
        const placement =
          position ??
          {
            x: 60 + (index % AUTO_PLACE_COLUMNS) * AUTO_PLACE_STEP_X,
            y: 60 + Math.floor(index / AUTO_PLACE_COLUMNS) * AUTO_PLACE_STEP_Y,
          };
        const node = createBuilderNode(type, placement);
        setSelectedNodeId(node.id);
        return { ...base, nodes: [...base.nodes, node] };
      });
      setDirty(true);
    },
    [],
  );

  const removeNode = useCallback(
    (nodeId: string) => {
      const base = graph ?? EMPTY_GRAPH;
      updateGraph({
        nodes: base.nodes.filter((node) => node.id !== nodeId),
        // Removing a node must take its edges with it; an edge left behind would publish as an orphan.
        edges: base.edges.filter((edge) => edge.source !== nodeId && edge.target !== nodeId),
      });
      setSelectedNodeId((current) => (current === nodeId ? null : current));
    },
    [graph, updateGraph],
  );

  const removeEdge = useCallback(
    (edgeId: string) => {
      const base = graph ?? EMPTY_GRAPH;
      updateGraph({ ...base, edges: base.edges.filter((edge) => edge.id !== edgeId) });
    },
    [graph, updateGraph],
  );

  const changeNodeConfig = useCallback(
    (nodeId: string, config: Record<string, unknown>) => {
      const base = graph ?? EMPTY_GRAPH;
      updateGraph({
        ...base,
        nodes: base.nodes.map((node) =>
          node.id === nodeId ? { ...node, data: { ...node.data, config } } : node,
        ),
      });
    },
    [graph, updateGraph],
  );

  const setEdgeCondition = useCallback(
    (edgeId: string, conditionExpr: string | null) => {
      const base = graph ?? EMPTY_GRAPH;
      updateGraph({
        ...base,
        edges: base.edges.map((edge) =>
          edge.id === edgeId
            ? { ...edge, label: conditionExpr ?? undefined, data: { conditionExpr } }
            : edge,
        ),
      });
    },
    [graph, updateGraph],
  );

  const save = useMutation({
    mutationFn: async () => {
      if (!draft) {
        throw new Error("This workflow has no editable draft version.");
      }
      return saveDraft(workflowId, draft.id, toSaveDraftPayload(graph ?? EMPTY_GRAPH));
    },
    onSuccess: (version) => {
      // The response carries the server's node ids; adopting them is what makes the *next* save work.
      const reseeded = builderGraphFromVersion(version);
      setSelectedNodeId((current) => remapNodeId((graph ?? EMPTY_GRAPH).nodes, reseeded.nodes, current));
      setGraph(reseeded);
      setDirty(false);
      setActionError(null);
      setDraftFailure(null);
      setPublishFailure(null);
      setNotice(
        `Draft saved: ${reseeded.nodes.length} node(s), ${reseeded.edges.length} edge(s).`,
      );
      queryClient.invalidateQueries({ queryKey: workflowKeys.detail(workflowId) });
    },
    onError: (error) => {
      setNotice(null);
      if (error instanceof WorkflowDraftError) {
        setDraftFailure(error);
        setActionError(null);
        return;
      }
      setDraftFailure(null);
      setActionError(extractErrorMessage(error, "Could not save this draft."));
    },
  });

  const publish = useMutation({
    mutationFn: async () => {
      if (!draft) {
        throw new Error("This workflow has no editable draft version.");
      }
      return publishVersion(workflowId, draft.id);
    },
    onSuccess: async (published) => {
      setPublishFailure(null);
      setDraftFailure(null);
      setActionError(null);
      setNotice(
        `Published version ${published.versionNumber}. A copy is now open as the next draft.`,
      );
      // The published version is frozen, so the editor has to re-target: publishing opens a successor
      // draft, and refetching is how this page learns which one it is.
      await queryClient.invalidateQueries({ queryKey: workflowKeys.all });
      setDirty(false);
    },
    onError: (error) => {
      setNotice(null);
      if (error instanceof WorkflowPublishError) {
        setPublishFailure(error);
        setActionError(null);
        return;
      }
      setPublishFailure(null);
      setActionError(extractErrorMessage(error, "Could not publish this version."));
    },
  });

  if (workflowQuery.isError && isForbiddenError(workflowQuery.error)) {
    return <NotAuthorized message="Only administrators and managers can edit workflows." />;
  }

  if (workflowQuery.isPending) {
    return (
      <p role="status" className="inline-flex items-center gap-2 text-sm text-gray-600">
        <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
        Loading workflow…
      </p>
    );
  }

  if (workflowQuery.isError) {
    const missing = isStatusError(workflowQuery.error, 404);
    return (
      <div className="mx-auto max-w-md text-center">
        <p role="alert" className="text-sm text-danger-700">
          {missing
            ? "That workflow does not exist."
            : extractErrorMessage(workflowQuery.error, "Could not load this workflow.")}
        </p>
        <Link href="/workflows" className="mt-3 inline-block text-sm text-primary-700 hover:underline">
          Back to workflows
        </Link>
      </div>
    );
  }

  const definition = workflowQuery.data;

  if (!draft) {
    return (
      <div className="mx-auto max-w-lg text-center">
        <h1 className="text-xl font-semibold text-gray-900">{definition.name}</h1>
        <p role="alert" className="mt-3 text-sm text-warning-800">
          Every version of this workflow is published, and a published version cannot be edited.
          Publishing normally opens the next draft; clone a version to carry its graph into a new
          workflow you can edit.
        </p>
        <Link
          href={`/workflows/${workflowId}`}
          className="mt-4 inline-block text-sm text-primary-700 hover:underline"
        >
          Back to version history
        </Link>
      </div>
    );
  }

  const selectedNode = currentGraph.nodes.find((node) => node.id === selectedNodeId) ?? null;
  const editingEdge = currentGraph.edges.find((edge) => edge.id === editingEdgeId) ?? null;
  const canPublish = user?.roleName === "ADMIN";
  const canReadUsers = user?.roleName === "ADMIN";

  const confirmLeave = (event: React.MouseEvent) => {
    if (!dirty) {
      return;
    }
    const leave = window.confirm("This canvas has unsaved changes. Leave without saving?");
    if (!leave) {
      event.preventDefault();
    }
  };

  return (
    <div className="mx-auto max-w-6xl">
      <Link
        href={`/workflows/${workflowId}`}
        onClick={confirmLeave}
        className="text-sm text-primary-700 hover:underline"
      >
        ← {definition.name}
      </Link>

      <div className="mt-2 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-primary-700">Workflow builder</h1>
          <p className="mt-1 text-sm text-gray-600">
            Editing draft version {draft.versionNumber}.{" "}
            {dirty ? "Unsaved changes." : "All changes saved."}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => save.mutate()}
            disabled={save.isPending}
            aria-busy={save.isPending}
            className="inline-flex items-center gap-2 rounded-md border border-gray-300 bg-white px-3 py-2 text-sm font-medium text-gray-800 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-60"
          >
            <Save aria-hidden="true" className="h-4 w-4" />
            {save.isPending ? "Saving…" : "Save draft"}
          </button>

          {canPublish ? (
            <button
              type="button"
              onClick={() => publish.mutate()}
              disabled={publish.isPending || dirty}
              aria-busy={publish.isPending}
              title={dirty ? "Save the draft before publishing." : undefined}
              className="inline-flex items-center gap-2 rounded-md bg-primary-600 px-3 py-2 text-sm font-medium text-white hover:bg-primary-700 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60"
            >
              <Upload aria-hidden="true" className="h-4 w-4" />
              {publish.isPending ? "Publishing…" : "Publish"}
            </button>
          ) : null}
        </div>
      </div>

      {dirty ? (
        <p className="mt-3 rounded-md bg-warning-50 px-3 py-2 text-sm text-warning-800">
          Unsaved changes. Publishing uses the stored draft, so save before publishing.
        </p>
      ) : null}
      {notice ? (
        <p role="status" className="mt-3 rounded-md bg-success-50 px-3 py-2 text-sm text-success-800">
          {notice}
        </p>
      ) : null}
      {actionError ? (
        <p role="alert" className="mt-3 rounded-md bg-danger-50 px-3 py-2 text-sm text-danger-700">
          {actionError}
        </p>
      ) : null}
      {draftFailure ? (
        <ViolationList
          title={draftFailure.message}
          violations={draftFailure.violations}
          graph={currentGraph}
        />
      ) : null}
      {publishFailure ? (
        <ViolationList
          title={publishFailure.message}
          violations={publishFailure.violations}
          graph={currentGraph}
        />
      ) : null}

      <div className="mt-4 grid gap-4 lg:grid-cols-[14rem_1fr_16rem]">
        <div className="space-y-6">
          <NodePalette onAddNode={(type) => addNode(type)} />
        </div>

        <div className="space-y-3">
          <BuilderCanvas
            graph={currentGraph}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onDropNode={(type, position) => addNode(type, position)}
            onSelectNode={setSelectedNodeId}
            onEditEdge={(edge) => setEditingEdgeId(edge.id)}
          />
          <p className="text-xs text-gray-600">
            {currentGraph.nodes.length} node(s), {currentGraph.edges.length} edge(s). Drag from a
            node&apos;s right handle to another node&apos;s left handle to connect them.
          </p>

          <SelectField
            id="selected-node"
            label="Selected node"
            value={selectedNodeId ?? ""}
            onChange={(event) => setSelectedNodeId(event.target.value || null)}
            hint="Selecting a node here opens its settings without using the canvas."
          >
            <option value="">Nothing selected</option>
            {currentGraph.nodes.map((node, index) => (
              <option key={node.id} value={node.id}>
                {index + 1}. {NODE_TYPE_LABELS[node.data.nodeType]}
              </option>
            ))}
          </SelectField>

          <section aria-labelledby="edge-list-heading">
            <h2 id="edge-list-heading" className="text-sm font-semibold text-gray-900">
              Edges
            </h2>
            {currentGraph.edges.length === 0 ? (
              <p className="mt-1 text-xs text-gray-600">No edges yet.</p>
            ) : (
              <ul className="mt-2 space-y-1">
                {currentGraph.edges.map((edge) => (
                  <li key={edge.id} className="flex flex-wrap items-center gap-2 text-xs">
                    <span className="text-gray-800">{describeEdge(currentGraph, edge)}</span>
                    {edge.data?.conditionExpr ? (
                      <span className="text-gray-500">when {edge.data.conditionExpr}</span>
                    ) : null}
                    <button
                      type="button"
                      onClick={() => setEditingEdgeId(edge.id)}
                      className="rounded-md border border-gray-300 px-2 py-0.5 font-medium text-gray-700 hover:bg-gray-50"
                    >
                      Edit condition on {describeEdge(currentGraph, edge)}
                    </button>
                    <button
                      type="button"
                      onClick={() => removeEdge(edge.id)}
                      className="rounded-md border border-gray-300 px-2 py-0.5 font-medium text-gray-700 hover:bg-gray-50"
                    >
                      Remove edge {describeEdge(currentGraph, edge)}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>

        <div className="rounded-xl border border-gray-200 bg-white p-3">
          <NodePropertiesPanel
            graph={currentGraph}
            node={selectedNode}
            canReadUsers={canReadUsers}
            onChangeConfig={changeNodeConfig}
            onRemoveNode={removeNode}
            onEditEdgeCondition={(edge) => setEditingEdgeId(edge.id)}
          />
        </div>
      </div>

      <EdgeConditionModal
        open={Boolean(editingEdge)}
        edgeDescription={editingEdge ? describeEdge(currentGraph, editingEdge) : ""}
        conditionExpr={editingEdge?.data?.conditionExpr ?? null}
        onClose={() => setEditingEdgeId(null)}
        onSave={(conditionExpr) => {
          if (editingEdge) {
            setEdgeCondition(editingEdge.id, conditionExpr);
          }
          setEditingEdgeId(null);
        }}
      />
    </div>
  );
}

/**
 * A refusal, listed rule by rule.
 *
 * Both the draft-save and publish endpoints report *every* violation at once, so they are rendered as
 * a list rather than a sentence. Where a message names a node id, the node's type is shown alongside
 * it: a designer reads "Approval", not a UUID.
 */
function ViolationList({
  title,
  violations,
  graph,
}: {
  title: string;
  violations: readonly string[];
  graph: BuilderGraph;
}) {
  const label = (violation: string): string | null => {
    const nodeId = violationNodeId(violation);
    if (!nodeId) {
      return null;
    }
    const node = graph.nodes.find((candidate) => candidate.id === nodeId);
    return node ? NODE_TYPE_LABELS[node.data.nodeType] : null;
  };

  return (
    <div role="alert" className="mt-3 rounded-md bg-danger-50 p-3 text-sm text-red-800">
      <p className="font-medium">{title}</p>
      {violations.length > 0 ? (
        <ul className="mt-2 list-disc space-y-1 pl-5">
          {violations.map((violation) => {
            const nodeLabel = label(violation);
            return (
              <li key={violation}>
                {nodeLabel ? <span className="font-medium">{nodeLabel}: </span> : null}
                {violation}
              </li>
            );
          })}
        </ul>
      ) : null}
    </div>
  );
}

export default WorkflowBuilder;
