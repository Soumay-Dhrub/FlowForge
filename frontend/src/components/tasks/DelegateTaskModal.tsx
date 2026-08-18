"use client";

/**
 * Hand this reviewer's work to somebody else for a period (Requirement 16.1).
 *
 * Worth being explicit in the UI about what this does, because the endpoint's path is misleading: it
 * names one task, but the effect is per-user. Every pending task the caller holds moves to the delegate,
 * and new work routes there too while the window is open. A reviewer who thought they were delegating
 * one item would be surprised, so the dialog says so and the confirmation reports how many changed
 * hands.
 *
 * The delegate list comes from `GET /api/users`, which is ADMIN-only. An EMPLOYEE therefore cannot be
 * offered a picker, so they type an id — ugly but honest, and better than a control that 403s. Widening
 * that endpoint is a backend decision, not something to work around here.
 */
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { extractErrorMessage } from "@/lib/api";
import { delegateTasks, localDateTimeToInstant } from "@/lib/instanceCollaborationApi";
import { fetchUsers, userKeys } from "@/lib/usersApi";
import { taskKeys } from "@/lib/tasksApi";
import { useAuth } from "@/context/AuthContext";
import Modal from "@/components/ui/Modal";
import SelectField from "@/components/ui/SelectField";
import SubmitButton from "@/components/ui/SubmitButton";
import TextField from "@/components/ui/TextField";

export function DelegateTaskModal({
  taskId,
  open,
  onClose,
}: {
  taskId: string;
  open: boolean;
  onClose: () => void;
}) {
  const { user } = useAuth();
  const queryClient = useQueryClient();

  const [delegateId, setDelegateId] = useState("");
  const [startAt, setStartAt] = useState("");
  const [endAt, setEndAt] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const canListUsers = user?.roleName === "ADMIN";
  const users = useQuery({
    queryKey: userKeys.list,
    queryFn: fetchUsers,
    enabled: open && canListUsers,
  });

  const delegate = useMutation({
    mutationFn: () =>
      delegateTasks(
        taskId,
        delegateId.trim(),
        localDateTimeToInstant(startAt),
        localDateTimeToInstant(endAt),
      ),
    onSuccess: (created) => {
      setNotice(
        created.reassignedTaskIds.length === 1
          ? "Delegated. 1 pending task changed hands."
          : `Delegated. ${created.reassignedTaskIds.length} pending tasks changed hands.`,
      );
      // Every task list is now potentially wrong: the caller's queue shrank and the delegate's grew.
      queryClient.invalidateQueries({ queryKey: taskKeys.all });
    },
  });

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setNotice(null);

    if (!delegateId.trim()) {
      setError("Choose who should cover for you.");
      return;
    }
    if (!startAt || !endAt) {
      setError("Give both a start and an end for the delegation.");
      return;
    }
    if (new Date(endAt) <= new Date(startAt)) {
      // The server refuses this too; saying it here avoids a round trip to learn something obvious.
      setError("The end must be after the start.");
      return;
    }
    if (user?.id && delegateId.trim() === user.id) {
      setError("You cannot delegate to yourself.");
      return;
    }

    try {
      await delegate.mutateAsync();
    } catch (failure) {
      setError(extractErrorMessage(failure, "Could not set up that delegation."));
    }
  }

  const others = (users.data ?? []).filter((candidate) => candidate.id !== user?.id);

  return (
    <Modal open={open} onClose={onClose} title="Delegate your tasks">
      <p className="text-sm text-gray-600">
        This moves <strong>all</strong> of your pending tasks to the person you choose, and routes new
        work to them until the delegation ends.
      </p>

      <form onSubmit={onSubmit} noValidate className="mt-4 space-y-4">
        {canListUsers ? (
          <SelectField
            id="delegate-user"
            label="Delegate to"
            value={delegateId}
            disabled={users.isPending}
            onChange={(event) => setDelegateId(event.target.value)}
          >
            <option value="">{users.isPending ? "Loading people…" : "Choose a person"}</option>
            {others.map((candidate) => (
              <option key={candidate.id} value={candidate.id}>
                {candidate.name} ({candidate.roleName})
              </option>
            ))}
          </SelectField>
        ) : (
          <TextField
            id="delegate-user"
            label="Delegate user id"
            value={delegateId}
            onChange={(event) => setDelegateId(event.target.value)}
            hint="Only administrators can browse the directory, so paste the person's user id."
          />
        )}

        <TextField
          id="delegate-start"
          label="From"
          type="datetime-local"
          value={startAt}
          onChange={(event) => setStartAt(event.target.value)}
        />
        <TextField
          id="delegate-end"
          label="Until"
          type="datetime-local"
          value={endAt}
          min={startAt || undefined}
          onChange={(event) => setEndAt(event.target.value)}
        />

        {error ? (
          <p role="alert" className="rounded-md bg-danger-50 px-3 py-2 text-sm text-danger-700">
            {error}
          </p>
        ) : null}
        {notice ? (
          <p role="status" className="rounded-md bg-success-50 px-3 py-2 text-sm text-success-800">
            {notice}
          </p>
        ) : null}

        <div className="flex gap-2">
          <SubmitButton isSubmitting={delegate.isPending} pendingLabel="Delegating…">
            Delegate
          </SubmitButton>
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
          >
            {notice ? "Done" : "Cancel"}
          </button>
        </div>
      </form>
    </Modal>
  );
}

export default DelegateTaskModal;
