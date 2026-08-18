"use client";

/**
 * Approve or reject a task (Requirements 13.1, 13.2).
 *
 * The "a rejection needs a comment" rule is enforced here *and* honoured when the server enforces it:
 * checking it client-side means the reviewer is told before a round trip, but the 400 is still handled,
 * because whitespace rules, a stale form, or a direct call are all reasons the server may be the one to
 * refuse. The server remains the authority — this is a courtesy, not the gate.
 *
 * Errors are placed where the reviewer can act on them: a missing comment lands on the comment field,
 * while "not yours" (403) and "already decided" (409) are facts about the task rather than the form, so
 * they surface at form level with the server's own wording.
 */
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { extractErrorMessage, isStatusError } from "@/lib/api";
import { instanceKeys } from "@/lib/instancesApi";
import { recordDecision, taskKeys } from "@/lib/tasksApi";
import SubmitButton from "@/components/ui/SubmitButton";
import type { Decision, Task } from "@/types";

const COMMENT_MAX = 5000;

const schema = z
  .object({
    decision: z.enum(["APPROVED", "REJECTED"], {
      errorMap: () => ({ message: "Choose whether to approve or reject" }),
    }),
    comment: z
      .string()
      .max(COMMENT_MAX, `Comment must be at most ${COMMENT_MAX} characters`),
  })
  .superRefine((values, ctx) => {
    // Mirrors the server's rule, whitespace included: a comment of spaces is not a justification.
    if (values.decision === "REJECTED" && values.comment.trim().length === 0) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["comment"],
        message: "A comment is required when rejecting a task",
      });
    }
  });

type FormValues = z.infer<typeof schema>;

const OPTIONS: ReadonlyArray<{ value: Decision; label: string; hint: string }> = [
  { value: "APPROVED", label: "Approve", hint: "The request continues to the next step." },
  { value: "REJECTED", label: "Reject", hint: "A comment is required." },
];

export function TaskDecisionForm({ task, onDecided }: { task: Task; onDecided?: (task: Task) => void }) {
  const queryClient = useQueryClient();

  const {
    register,
    handleSubmit,
    setError,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { comment: "" },
  });

  const decision = watch("decision");

  const mutation = useMutation({
    mutationFn: (values: FormValues) => recordDecision(task.id, values.decision, values.comment),
    onSuccess: (decided) => {
      // The list, this task, and the instance the decision advanced are all now stale.
      queryClient.invalidateQueries({ queryKey: taskKeys.all });
      queryClient.invalidateQueries({ queryKey: instanceKeys.detail(task.instanceId) });
      onDecided?.(decided);
    },
  });

  const onSubmit = handleSubmit(async (values) => {
    try {
      await mutation.mutateAsync(values);
    } catch (error) {
      if (isStatusError(error, 400)) {
        setError("comment", {
          message: extractErrorMessage(error, "A comment is required when rejecting a task"),
        });
        return;
      }
      setError("root", {
        message: extractErrorMessage(error, "Could not record the decision. Try again."),
      });
    }
  });

  const decisionErrorId = "task-decision-error";
  const commentErrorId = "task-decision-comment-error";
  const commentHintId = "task-decision-comment-hint";
  const commentDescribedBy = [commentHintId, errors.comment ? commentErrorId : null]
    .filter(Boolean)
    .join(" ");

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4">
      <fieldset className="space-y-2">
        <legend className="text-sm font-medium text-gray-700">Decision</legend>
        {OPTIONS.map((option) => (
          <label key={option.value} htmlFor={`task-decision-${option.value}`} className="flex gap-2">
            <input
              id={`task-decision-${option.value}`}
              type="radio"
              value={option.value}
              // No `aria-invalid` here: it is not a supported property of role `radio`. The group's
              // error is announced through `aria-describedby` instead.
              aria-describedby={errors.decision ? decisionErrorId : undefined}
              className="mt-1 h-4 w-4 border-gray-300 text-primary-600 focus:ring-primary-500"
              {...register("decision")}
            />
            <span className="text-sm">
              <span className="font-medium text-gray-900">{option.label}</span>
              <span className="block text-xs text-gray-500">{option.hint}</span>
            </span>
          </label>
        ))}
        {errors.decision?.message ? (
          <p id={decisionErrorId} role="alert" className="text-sm text-danger-700">
            {errors.decision.message}
          </p>
        ) : null}
      </fieldset>

      <div className="space-y-1">
        <label htmlFor="task-decision-comment" className="block text-sm font-medium text-gray-700">
          Comment
        </label>
        <textarea
          id="task-decision-comment"
          rows={4}
          aria-invalid={errors.comment ? true : undefined}
          aria-describedby={commentDescribedBy}
          className={`w-full rounded-md border px-3 py-2 text-gray-900 shadow-sm outline-none focus:ring-2 focus:ring-primary-500 ${
            errors.comment ? "border-danger-600" : "border-gray-300"
          }`}
          {...register("comment")}
        />
        <p id={commentHintId} className="text-xs text-gray-500">
          {decision === "REJECTED"
            ? "Required: explain why this request is being rejected."
            : "Optional when approving."}
        </p>
        {errors.comment?.message ? (
          <p id={commentErrorId} role="alert" className="text-sm text-danger-700">
            {errors.comment.message}
          </p>
        ) : null}
      </div>

      {errors.root?.message ? (
        <p role="alert" className="rounded-md bg-danger-50 px-3 py-2 text-sm text-danger-700">
          {errors.root.message}
        </p>
      ) : null}

      <SubmitButton isSubmitting={isSubmitting} pendingLabel="Recording…">
        Record decision
      </SubmitButton>
    </form>
  );
}

export default TaskDecisionForm;
