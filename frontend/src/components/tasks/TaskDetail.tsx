"use client";

/**
 * Task detail: what is being decided, and the decision (Requirements 12.1, 13.1, 13.2).
 *
 * The task itself only carries routing metadata — workflow, node, status, dates. The substance of the
 * request is the instance's `requestData`, so it is fetched separately and rendered generically: the
 * payload is an open map defined by whatever form started the workflow, and assuming keys here would
 * mean showing nothing for every workflow that does not happen to match.
 *
 * `GET /api/instances/{id}` is restricted to the initiator or a privileged role, so an EMPLOYEE
 * deciding a task on someone else's request is answered 403. That is a missing *section*, not a broken
 * page: the decision form still works, because deciding is checked on task ownership, not on being
 * able to read the request.
 *
 * <p>Attachments and the discussion sit below the decision, and both are restricted to participants of
 * the instance. They are rendered only when the instance itself loaded: a 403 there means the caller is
 * not a participant, so those endpoints would refuse them too, and offering the controls anyway would
 * invite an error instead of explaining one.
 */
import { useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Loader2, UserRoundPlus } from "lucide-react";
import { extractErrorMessage, isForbiddenError, isStatusError } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { INSTANCE_STATUS_LABELS, fetchInstance, instanceKeys } from "@/lib/instancesApi";
import {
  DECISION_LABELS,
  TASK_STATUS_LABELS,
  getTask,
  isDecidableBy,
  taskKeys,
} from "@/lib/tasksApi";
import { useAuth } from "@/context/AuthContext";
import TaskDecisionForm from "@/components/tasks/TaskDecisionForm";
import DelegateTaskModal from "@/components/tasks/DelegateTaskModal";
import AttachmentPanel from "@/components/instances/AttachmentPanel";
import CommentThread from "@/components/instances/CommentThread";
import type { Task } from "@/types";

export function TaskDetail({ taskId }: { taskId: string }) {
  const { user } = useAuth();
  const [notice, setNotice] = useState<string | null>(null);
  const [delegating, setDelegating] = useState(false);

  const task = useQuery({
    queryKey: taskKeys.detail(taskId),
    queryFn: () => getTask(taskId),
  });

  const instanceId = task.data?.instanceId;
  const instance = useQuery({
    queryKey: instanceKeys.detail(instanceId ?? ""),
    queryFn: () => fetchInstance(instanceId as string),
    enabled: Boolean(instanceId),
  });

  if (task.isPending) {
    return (
      <p role="status" className="inline-flex items-center gap-2 text-sm text-gray-600">
        <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
        Loading task…
      </p>
    );
  }

  if (task.isError) {
    const missing = isStatusError(task.error, 404);
    return (
      <div className="mx-auto max-w-md text-center">
        <p role="alert" className="text-sm text-danger-700">
          {missing
            ? "That task does not exist."
            : extractErrorMessage(task.error, "Could not load this task.")}
        </p>
        {missing ? (
          <Link href="/tasks" className="mt-3 inline-block text-sm text-primary-700 hover:underline">
            Back to tasks
          </Link>
        ) : (
          <button
            type="button"
            onClick={() => task.refetch()}
            className="mt-3 rounded-md border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50"
          >
            Try again
          </button>
        )}
      </div>
    );
  }

  const detail = task.data;
  const decidable = isDecidableBy(detail, user?.id);
  const mine = detail.assignedToId === user?.id;

  return (
    <div className="mx-auto max-w-3xl">
      <Link href="/tasks" className="text-sm text-primary-700 hover:underline">
        ← Tasks
      </Link>

      <h1 className="mt-2 text-2xl font-bold text-primary-700">{detail.workflowName}</h1>
      <p className="mt-1 text-sm text-gray-600">{detail.nodeLabel ?? detail.nodeType}</p>

      <dl className="mt-4 flex flex-wrap gap-x-8 gap-y-2 text-sm">
        <div>
          <dt className="text-gray-500">Status</dt>
          <dd className="font-medium text-gray-900">{TASK_STATUS_LABELS[detail.status]}</dd>
        </div>
        <div>
          <dt className="text-gray-500">Step type</dt>
          <dd className="font-medium text-gray-900">{detail.nodeType}</dd>
        </div>
        <div>
          <dt className="text-gray-500">Assigned to</dt>
          <dd className="font-medium text-gray-900">{mine ? "You" : "Another user"}</dd>
        </div>
        <div>
          <dt className="text-gray-500">Due</dt>
          <dd className="font-medium text-gray-900">{formatDateTime(detail.dueAt)}</dd>
        </div>
        <div>
          <dt className="text-gray-500">Raised</dt>
          <dd className="font-medium text-gray-900">{formatDateTime(detail.createdAt)}</dd>
        </div>
      </dl>

      <section className="mt-8">
        <h2 className="text-lg font-semibold text-gray-900">The request</h2>

        {instance.isPending ? (
          <p role="status" className="mt-2 inline-flex items-center gap-2 text-sm text-gray-600">
            <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
            Loading the request…
          </p>
        ) : null}

        {instance.isError && isForbiddenError(instance.error) ? (
          <p className="mt-2 rounded-md bg-warning-50 px-3 py-2 text-sm text-warning-800">
            You do not have access to this request&apos;s details. You can still record your decision
            below.
          </p>
        ) : null}

        {instance.isError && !isForbiddenError(instance.error) ? (
          <div className="mt-2">
            <p role="alert" className="text-sm text-danger-700">
              {extractErrorMessage(instance.error, "Could not load the request behind this task.")}
            </p>
            <button
              type="button"
              onClick={() => instance.refetch()}
              className="mt-2 rounded-md border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50"
            >
              Try again
            </button>
          </div>
        ) : null}

        {instance.isSuccess ? (
          <>
            <dl className="mt-2 flex flex-wrap gap-x-8 gap-y-2 text-sm">
              <div>
                <dt className="text-gray-500">Submitted by</dt>
                <dd className="font-medium text-gray-900">
                  {instance.data.initiatorName ?? "—"}
                </dd>
              </div>
              <div>
                <dt className="text-gray-500">Submitted</dt>
                <dd className="font-medium text-gray-900">
                  {formatDateTime(instance.data.startedAt)}
                </dd>
              </div>
              <div>
                <dt className="text-gray-500">Request status</dt>
                <dd className="font-medium text-gray-900">
                  {INSTANCE_STATUS_LABELS[instance.data.status]}
                </dd>
              </div>
              <div>
                <dt className="text-gray-500">Workflow version</dt>
                <dd className="font-medium text-gray-900">
                  {instance.data.versionNumber === null ? "—" : `v${instance.data.versionNumber}`}
                </dd>
              </div>
            </dl>
            <RequestData data={instance.data.requestData} />
          </>
        ) : null}
      </section>

      <section className="mt-8">
        <h2 className="text-lg font-semibold text-gray-900">Decision</h2>

        {notice ? (
          <p role="status" className="mt-2 rounded-md bg-success-50 px-3 py-2 text-sm text-success-800">
            {notice}
          </p>
        ) : null}

        {decidable ? (
          <div className="mt-3">
            <TaskDecisionForm
              task={detail}
              onDecided={(decided) =>
                setNotice(
                  `Recorded ${(decided.decision ? DECISION_LABELS[decided.decision] : "your decision").toLowerCase()}.`,
                )
              }
            />

            {/* Delegation is offered only while the task is still the caller's to act on: handing over
                work you have already decided, or that is not yours, is meaningless. */}
            <button
              type="button"
              onClick={() => setDelegating(true)}
              className="mt-4 inline-flex items-center gap-2 rounded-md border border-gray-300 px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
            >
              <UserRoundPlus aria-hidden="true" className="h-4 w-4" />
              Delegate my tasks
            </button>
            <DelegateTaskModal
              taskId={detail.id}
              open={delegating}
              onClose={() => setDelegating(false)}
            />
          </div>
        ) : (
          <RecordedDecision task={detail} mine={mine} />
        )}
      </section>

      {/* Participant-only sections. A 403 on the instance means these would refuse the caller too. */}
      {instance.isSuccess ? (
        <>
          <AttachmentPanel instanceId={detail.instanceId} />
          <CommentThread instanceId={detail.instanceId} />
        </>
      ) : null}
    </div>
  );
}

/**
 * The submitted payload, rendered without assuming its shape.
 *
 * Scalars are printed as themselves; nested objects and arrays are shown as formatted JSON rather than
 * flattened, because inventing a layout for an unknown structure risks hiding part of it.
 */
function RequestData({ data }: { data: Record<string, unknown> | null }) {
  const entries = Object.entries(data ?? {});

  if (entries.length === 0) {
    return <p className="mt-4 text-sm text-gray-600">This request carries no submitted data.</p>;
  }

  return (
    <dl className="mt-4 divide-y divide-gray-200 rounded-xl border border-gray-200 bg-white text-sm">
      {entries.map(([key, value]) => (
        <div key={key} className="grid gap-1 px-4 py-3 sm:grid-cols-3">
          <dt className="font-medium text-gray-700">{key}</dt>
          <dd className="text-gray-900 sm:col-span-2">
            <RequestValue value={value} />
          </dd>
        </div>
      ))}
    </dl>
  );
}

function RequestValue({ value }: { value: unknown }) {
  if (value === null || value === undefined) {
    return <span className="text-gray-500">—</span>;
  }
  if (typeof value === "object") {
    return (
      <pre className="overflow-x-auto whitespace-pre-wrap break-words text-xs text-gray-800">
        {JSON.stringify(value, null, 2)}
      </pre>
    );
  }
  if (typeof value === "boolean") {
    return <>{value ? "Yes" : "No"}</>;
  }
  return <>{String(value)}</>;
}

/**
 * What is shown instead of the form.
 *
 * Three genuinely different situations, told apart rather than collapsed into one grey box: the task
 * has been decided, it is still open but belongs to somebody else, or it is closed without a decision
 * (cancelled with its instance, or reassigned away).
 */
function RecordedDecision({ task, mine }: { task: Task; mine: boolean }) {
  if (task.decision) {
    return (
      <div className="mt-3 rounded-xl border border-gray-200 bg-white p-4 text-sm">
        <p className="font-medium text-gray-900">{DECISION_LABELS[task.decision]}</p>
        <p className="mt-2 whitespace-pre-wrap text-gray-700">
          {task.comment ?? "No comment was left."}
        </p>
      </div>
    );
  }

  if (!mine) {
    return (
      <p className="mt-3 text-sm text-gray-600">
        This task is assigned to someone else, so only they can decide it.
      </p>
    );
  }

  return (
    <p className="mt-3 text-sm text-gray-600">
      This task is {TASK_STATUS_LABELS[task.status].toLowerCase()} and can no longer be decided.
    </p>
  );
}

export default TaskDetail;
