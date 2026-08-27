"use client";

import { useQuery } from "@tanstack/react-query";
import { Trash2 } from "lucide-react";
import SelectField from "@/components/ui/SelectField";
import TextField from "@/components/ui/TextField";
import { fetchRoles, referenceDataKeys } from "@/lib/referenceDataApi";
import { fetchUsers, userKeys } from "@/lib/usersApi";
import {
  NODE_TYPE_LABELS,
  describeEdge,
  hasConfigurableFields,
  type BuilderEdge,
  type BuilderGraph,
  type BuilderNode,
} from "@/lib/workflowGraph";

const MAX_EVENT_TYPE_LENGTH = 50;

interface NodePropertiesPanelProps {
  graph: BuilderGraph;
  node: BuilderNode | null;
  /** True for an ADMIN: `GET /api/users` is ADMIN-only, so only they get the user pickers. */
  canReadUsers: boolean;
  onChangeConfig: (nodeId: string, config: Record<string, unknown>) => void;
  onRemoveNode: (nodeId: string) => void;
  onEditEdgeCondition: (edge: BuilderEdge) => void;
}

export function NodePropertiesPanel({
  graph,
  node,
  canReadUsers,
  onChangeConfig,
  onRemoveNode,
  onEditEdgeCondition,
}: NodePropertiesPanelProps) {
  const roles = useQuery({ queryKey: referenceDataKeys.roles, queryFn: fetchRoles });
  const users = useQuery({ queryKey: userKeys.list, queryFn: fetchUsers, enabled: canReadUsers });

  if (!node) {
    return (
      <section aria-labelledby="node-properties-heading">
        <h2 id="node-properties-heading" className="text-sm font-semibold text-gray-900">
          Node settings
        </h2>
        <p className="mt-1 text-xs text-gray-600">
          Select a node to configure it. Nodes without an assignee cannot be published.
        </p>
      </section>
    );
  }

  const config = node.data.config ?? {};
  const nodeType = node.data.nodeType;
  const activeUsers = (users.data ?? []).filter((candidate) => candidate.isActive);

  /** Write one key, removing it entirely when the value is empty. */
  const setValue = (key: string, value: unknown) => {
    const next = { ...config };
    const isEmpty =
      value === undefined ||
      value === null ||
      value === "" ||
      (Array.isArray(value) && value.length === 0);
    if (isEmpty) {
      delete next[key];
    } else {
      next[key] = value;
    }
    onChangeConfig(node.id, next);
  };

  const stringValue = (key: string): string => {
    const raw = config[key];
    return raw === undefined || raw === null ? "" : String(raw);
  };

  const listValue = (key: string): string[] => {
    const raw = config[key];
    if (Array.isArray(raw)) {
      return raw.map((entry) => String(entry));
    }
    return raw === undefined || raw === null || raw === "" ? [] : [String(raw)];
  };

  const timeoutRaw = stringValue("timeoutMinutes");
  const timeoutError =
    timeoutRaw !== "" && !/^\d+$/.test(timeoutRaw.trim())
      ? "Enter a whole number of minutes."
      : timeoutRaw.trim() === "0"
        ? "A timeout must be greater than zero."
        : undefined;

  const eventType = stringValue("eventType");
  const eventTypeError =
    eventType.length > MAX_EVENT_TYPE_LENGTH
      ? `Use ${MAX_EVENT_TYPE_LENGTH} characters or fewer.`
      : undefined;

  const roleOptions = (
    <>
      <option value="">Not set</option>
      {(roles.data ?? []).map((role) => (
        <option key={role.id} value={role.name}>
          {role.name}
        </option>
      ))}
    </>
  );

  const userOptions = (
    <>
      <option value="">Not set</option>
      {activeUsers.map((candidate) => (
        <option key={candidate.id} value={candidate.id}>
          {candidate.name} ({candidate.email})
        </option>
      ))}
    </>
  );

  /** The "a specific user, or a role" pair that Task and Approval nodes both configure. */
  const assignmentFields = (userIdKey: string, roleKey: string, audience: string) => (
    <>
      {canReadUsers ? (
        <SelectField
          id={`config-${userIdKey}`}
          label={`${audience} (specific user)`}
          value={stringValue(userIdKey)}
          onChange={(event) => setValue(userIdKey, event.target.value)}
          hint={users.isError ? "Could not load users. Assign by role instead." : undefined}
        >
          {userOptions}
        </SelectField>
      ) : stringValue(userIdKey) ? (
        <div className="space-y-1">
          <TextField
            id={`config-${userIdKey}`}
            label={`${audience} (user id set by an administrator)`}
            value={stringValue(userIdKey)}
            readOnly
            hint="Only administrators can look up users, so this id cannot be changed here."
          />
          <button
            type="button"
            onClick={() => setValue(userIdKey, "")}
            className="text-xs font-medium text-primary-700 hover:underline"
          >
            Clear this user and assign by role
          </button>
        </div>
      ) : null}

      <SelectField
        id={`config-${roleKey}`}
        label={`${audience} role`}
        value={stringValue(roleKey)}
        onChange={(event) => setValue(roleKey, event.target.value)}
        hint={
          canReadUsers
            ? "Used when no specific user is set."
            : "Managers assign by role; a specific user requires an administrator."
        }
      >
        {roleOptions}
      </SelectField>

      <TextField
        id="config-timeoutMinutes"
        label="Timeout (minutes)"
        type="number"
        min={1}
        step={1}
        value={timeoutRaw}
        error={timeoutError}
        hint="Leave blank for no deadline."
        onChange={(event) => {
          const raw = event.target.value.trim();
          setValue("timeoutMinutes", raw === "" ? "" : Number(raw));
        }}
      />
    </>
  );

  const outgoing = graph.edges.filter((edge) => edge.source === node.id);

  return (
    <section aria-labelledby="node-properties-heading" className="space-y-4">
      <div className="flex items-start justify-between gap-2">
        <div>
          <h2 id="node-properties-heading" className="text-sm font-semibold text-gray-900">
            {NODE_TYPE_LABELS[nodeType]} settings
          </h2>
          <p className="mt-1 text-xs text-gray-600">
            {hasConfigurableFields(nodeType)
              ? "These values are validated when the workflow is published."
              : "This node type needs no configuration."}
          </p>
        </div>
        <button
          type="button"
          onClick={() => onRemoveNode(node.id)}
          className="inline-flex items-center gap-1 rounded-md border border-gray-300 px-2 py-1 text-xs font-medium text-gray-700 hover:bg-gray-50"
        >
          <Trash2 aria-hidden="true" className="h-3.5 w-3.5" />
          Remove node
        </button>
      </div>

      {nodeType === "TASK" ? assignmentFields("assigneeUserId", "assigneeRole", "Assignee") : null}
      {nodeType === "APPROVAL"
        ? assignmentFields("approverUserId", "approverRole", "Approver")
        : null}

      {nodeType === "NOTIFICATION" ? (
        <>
          {canReadUsers ? (
            <SelectField
              id="config-recipientUserIds"
              label="Recipients (specific users)"
              multiple
              size={4}
              value={listValue("recipientUserIds")}
              onChange={(event) =>
                setValue(
                  "recipientUserIds",
                  Array.from(event.target.selectedOptions, (option) => option.value).filter(Boolean),
                )
              }
              hint="Leave empty to notify the request initiator."
            >
              {activeUsers.map((candidate) => (
                <option key={candidate.id} value={candidate.id}>
                  {candidate.name} ({candidate.email})
                </option>
              ))}
            </SelectField>
          ) : null}

          <SelectField
            id="config-recipientRoles"
            label="Recipient roles"
            multiple
            size={3}
            value={listValue("recipientRoles")}
            onChange={(event) =>
              setValue(
                "recipientRoles",
                Array.from(event.target.selectedOptions, (option) => option.value).filter(Boolean),
              )
            }
            hint="Every active member of the selected roles is notified."
          >
            {(roles.data ?? []).map((role) => (
              <option key={role.id} value={role.name}>
                {role.name}
              </option>
            ))}
          </SelectField>

          <TextField
            id="config-eventType"
            label="Event type"
            value={eventType}
            error={eventTypeError}
            maxLength={MAX_EVENT_TYPE_LENGTH}
            hint="Optional. Defaults to the platform's generic workflow notification."
            onChange={(event) => setValue("eventType", event.target.value)}
          />

          <TextField
            id="config-message"
            label="Message"
            value={stringValue("message")}
            hint="Optional text carried in the notification payload."
            onChange={(event) => setValue("message", event.target.value)}
          />
        </>
      ) : null}

      {nodeType === "CONDITION" ? (
        <div>
          <h3 className="text-xs font-semibold uppercase tracking-wide text-gray-500">
            Outgoing conditions
          </h3>
          <p className="mt-1 text-xs text-gray-600">
            Evaluated top to bottom; the first expression that holds is followed. A Condition node
            with no outgoing edge cannot be published.
          </p>
          {outgoing.length === 0 ? (
            <p className="mt-2 text-xs text-amber-700">
              This node has no outgoing edges yet. Connect it to another node.
            </p>
          ) : (
            <ul className="mt-2 space-y-2">
              {outgoing.map((edge) => (
                <li key={edge.id}>
                  <button
                    type="button"
                    onClick={() => onEditEdgeCondition(edge)}
                    className="w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-left text-xs font-medium text-gray-800 hover:bg-gray-50"
                  >
                    Edit condition on {describeEdge(graph, edge)}
                    <span className="mt-0.5 block font-normal text-gray-600">
                      {edge.data?.conditionExpr ?? "No condition (always taken)"}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : null}
    </section>
  );
}

export default NodePropertiesPanel;
