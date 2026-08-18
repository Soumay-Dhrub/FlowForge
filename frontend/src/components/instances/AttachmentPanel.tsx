"use client";

/**
 * The files on a request (Requirements 14.1, 14.2, 14.3).
 *
 * Size and type are checked here before the upload starts, which is a courtesy rather than a control:
 * the server counts bytes as it writes and matches the type against its own allow-list, so a client
 * that skipped these checks would be refused anyway. Doing them first saves pushing a 40 MB file up a
 * slow connection only to be told it was too large.
 *
 * The limits are stated in the UI rather than only enforced. A reviewer who cannot see what is allowed
 * finds out by failing, which is a worse way to learn it.
 */
import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, Paperclip, Upload } from "lucide-react";
import { extractErrorMessage } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import {
  collaborationKeys,
  formatFileSize,
  listAttachments,
  uploadAttachment,
} from "@/lib/instanceCollaborationApi";

/** Mirrors app.attachment.max-size-bytes. The server remains the authority. */
const MAX_SIZE_BYTES = 10 * 1024 * 1024;

/** Mirrors app.attachment.allowed-types. */
const ALLOWED_TYPES: Record<string, string> = {
  "application/pdf": "PDF",
  "image/jpeg": "JPEG",
  "image/png": "PNG",
  "application/msword": "DOC",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document": "DOCX",
};

export function AttachmentPanel({ instanceId }: { instanceId: string }) {
  const queryClient = useQueryClient();
  const inputRef = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const attachments = useQuery({
    queryKey: collaborationKeys.attachments(instanceId),
    queryFn: () => listAttachments(instanceId),
  });

  const upload = useMutation({
    mutationFn: (file: File) => uploadAttachment(instanceId, file),
    onSuccess: (added) => {
      setNotice(`Attached ${added.fileName}.`);
      queryClient.invalidateQueries({ queryKey: collaborationKeys.attachments(instanceId) });
    },
  });

  /** Reject what the server would reject, with the same reason, before spending the upload. */
  function localRejection(file: File): string | null {
    if (file.size > MAX_SIZE_BYTES) {
      return `${file.name} is ${formatFileSize(file.size)}; the limit is ${formatFileSize(MAX_SIZE_BYTES)}.`;
    }
    // An empty type means the browser could not tell. Let the server decide rather than guessing from
    // the extension, which is exactly the check that must not be trusted.
    if (file.type && !ALLOWED_TYPES[file.type]) {
      return `${file.name} is a ${file.type} file, which is not an allowed type.`;
    }
    return null;
  }

  async function onFileChosen(file: File | undefined) {
    setError(null);
    setNotice(null);
    if (!file) {
      return;
    }

    const rejection = localRejection(file);
    if (rejection) {
      setError(rejection);
      resetInput();
      return;
    }

    try {
      await upload.mutateAsync(file);
    } catch (failure) {
      setError(extractErrorMessage(failure, "Could not upload that file."));
    } finally {
      resetInput();
    }
  }

  /** Clear the input so choosing the same file again still fires a change event. */
  function resetInput() {
    if (inputRef.current) {
      inputRef.current.value = "";
    }
  }

  const rows = attachments.data ?? [];

  return (
    <section className="mt-8">
      <h2 className="text-lg font-semibold text-gray-900">Attachments</h2>
      <p className="mt-1 text-xs text-gray-500">
        Up to {formatFileSize(MAX_SIZE_BYTES)} per file. Allowed:{" "}
        {Object.values(ALLOWED_TYPES).join(", ")}.
      </p>

      <div className="mt-3">
        <label
          htmlFor="attachment-file"
          className="inline-flex cursor-pointer items-center gap-2 rounded-md border border-gray-300 px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 focus-within:ring-2 focus-within:ring-primary-500"
        >
          {upload.isPending ? (
            <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
          ) : (
            <Upload aria-hidden="true" className="h-4 w-4" />
          )}
          {upload.isPending ? "Uploading…" : "Attach a file"}
        </label>
        <input
          ref={inputRef}
          id="attachment-file"
          type="file"
          className="sr-only"
          disabled={upload.isPending}
          accept={Object.keys(ALLOWED_TYPES).join(",")}
          aria-describedby={error ? "attachment-error" : undefined}
          aria-invalid={error ? true : undefined}
          onChange={(event) => void onFileChosen(event.target.files?.[0])}
        />
      </div>

      {error ? (
        <p id="attachment-error" role="alert" className="mt-2 text-sm text-red-700">
          {error}
        </p>
      ) : null}
      {notice ? (
        <p role="status" className="mt-2 text-sm text-green-800">
          {notice}
        </p>
      ) : null}

      {attachments.isPending ? (
        <p role="status" className="mt-3 inline-flex items-center gap-2 text-sm text-gray-600">
          <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
          Loading attachments…
        </p>
      ) : null}

      {attachments.isError ? (
        <p role="alert" className="mt-3 text-sm text-red-700">
          {extractErrorMessage(attachments.error, "Could not load the attachments.")}
        </p>
      ) : null}

      {attachments.isSuccess && rows.length === 0 ? (
        <p className="mt-3 text-sm text-gray-600">Nothing attached to this request yet.</p>
      ) : null}

      {rows.length > 0 ? (
        <ul className="mt-3 divide-y divide-gray-200 rounded-lg border border-gray-200 bg-white text-sm">
          {rows.map((attachment) => (
            <li key={attachment.id} className="flex items-center gap-3 px-4 py-3">
              <Paperclip aria-hidden="true" className="h-4 w-4 shrink-0 text-gray-400" />
              <span className="min-w-0 flex-1 truncate font-medium text-gray-900">
                {attachment.fileName}
              </span>
              <span className="shrink-0 text-gray-600">{formatFileSize(attachment.fileSize)}</span>
              <span className="shrink-0 text-gray-500">{formatDateTime(attachment.createdAt)}</span>
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  );
}

export default AttachmentPanel;
