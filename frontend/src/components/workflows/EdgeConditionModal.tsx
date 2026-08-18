"use client";

/**
 * The optional condition expression on an edge (Requirements 6.2, 6.3).
 *
 * Every edge may carry one, so the dialog is offered for all of them rather than only for a Condition
 * node's edges — the engine reads `condition_expr` wherever it finds it. Leaving it blank clears the
 * condition, which is how an unconditional fallback branch is expressed.
 *
 * The expression is not validated here. It is SpEL, evaluated server side, and publishing already
 * reports an expression that will not parse as a violation naming the edge — a second, weaker parser
 * in the browser would only disagree with the real one.
 */
import { useEffect, useState } from "react";
import Modal from "@/components/ui/Modal";
import TextField from "@/components/ui/TextField";

export function EdgeConditionModal({
  open,
  edgeDescription,
  conditionExpr,
  onClose,
  onSave,
}: {
  open: boolean;
  /** e.g. "Condition → Approval", so the dialog says which edge is being edited. */
  edgeDescription: string;
  conditionExpr: string | null;
  onClose: () => void;
  onSave: (conditionExpr: string | null) => void;
}) {
  const [value, setValue] = useState(conditionExpr ?? "");

  // Re-seed when a different edge is opened; the dialog is reused, not remounted per edge.
  useEffect(() => {
    if (open) {
      setValue(conditionExpr ?? "");
    }
  }, [open, conditionExpr]);

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Edge condition"
      description={`Optional expression on ${edgeDescription}. Leave blank for an unconditional edge.`}
    >
      <form
        onSubmit={(event) => {
          event.preventDefault();
          const trimmed = value.trim();
          onSave(trimmed === "" ? null : trimmed);
        }}
        className="space-y-4"
      >
        <TextField
          id="edge-condition"
          label="Condition expression"
          value={value}
          onChange={(event) => setValue(event.target.value)}
          placeholder="amount > 1000"
          hint="Boolean expression over the request data, evaluated when the instance reaches the edge."
        />
        <div className="flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-gray-300 px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            type="submit"
            className="rounded-md bg-primary-600 px-3 py-2 text-sm font-medium text-white hover:bg-primary-700 focus:ring-offset-2"
          >
            Save condition
          </button>
        </div>
      </form>
    </Modal>
  );
}

export default EdgeConditionModal;
