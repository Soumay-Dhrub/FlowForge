"use client";

/** Create-workflow dialog. Creation only names the process; the graph is authored afterwards. */
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { extractErrorMessage } from "@/lib/api";
import { createWorkflow, workflowKeys } from "@/lib/workflowsApi";
import Modal from "@/components/ui/Modal";
import SubmitButton from "@/components/ui/SubmitButton";
import TextField from "@/components/ui/TextField";
import type { Workflow } from "@/types";

const schema = z.object({
  name: z
    .string()
    .trim()
    .min(1, "Name is required")
    .max(150, "Name must not exceed 150 characters"),
  description: z.string().trim().optional(),
});

type FormValues = z.infer<typeof schema>;

interface CreateWorkflowModalProps {
  open: boolean;
  onClose: () => void;
  onCreated?: (workflow: Workflow) => void;
}

export function CreateWorkflowModal({ open, onClose, onCreated }: CreateWorkflowModalProps) {
  const queryClient = useQueryClient();
  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: { name: "", description: "" } });

  const mutation = useMutation({
    mutationFn: createWorkflow,
    onSuccess: (workflow) => {
      queryClient.invalidateQueries({ queryKey: workflowKeys.all });
      reset();
      onCreated?.(workflow);
      onClose();
    },
  });

  const onSubmit = handleSubmit(async (values) => {
    try {
      await mutation.mutateAsync(values);
    } catch (error) {
      // Server-side validation lands on the field it belongs to where possible.
      setError("root", {
        message: extractErrorMessage(error, "Could not create the workflow. Try again."),
      });
    }
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="New workflow"
      description="Name the process now; add its nodes and edges afterwards."
    >
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <TextField
          id="workflow-name"
          label="Name"
          autoComplete="off"
          error={errors.name?.message}
          {...register("name")}
        />
        <div className="space-y-1">
          <label htmlFor="workflow-description" className="block text-sm font-medium text-gray-700">
            Description <span className="text-gray-400">(optional)</span>
          </label>
          <textarea
            id="workflow-description"
            rows={3}
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-gray-900 shadow-sm outline-none focus:ring-2 focus:ring-primary-500"
            {...register("description")}
          />
        </div>

        {errors.root?.message ? (
          <p role="alert" className="text-sm text-danger-700">
            {errors.root.message}
          </p>
        ) : null}

        <SubmitButton isSubmitting={isSubmitting} pendingLabel="Creating…">
          Create workflow
        </SubmitButton>
      </form>
    </Modal>
  );
}

export default CreateWorkflowModal;
