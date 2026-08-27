import api, { unwrap } from "@/lib/api";
import type {
  ApiResponse,
  Attachment,
  Comment,
  Delegation,
} from "@/types";

/** Query keys, so posting a comment or uploading a file invalidates exactly what is now stale. */
export const collaborationKeys = {
  attachments: (instanceId: string) => ["instances", instanceId, "attachments"] as const,
  comments: (instanceId: string) => ["instances", instanceId, "comments"] as const,
};

// ── Attachments (Requirements 14.1–14.3) ──────────────────────────────────────

/** `GET /api/instances/{id}/attachments` — the files on a request, for participants only. */
export async function listAttachments(instanceId: string): Promise<Attachment[]> {
  return unwrap(
    await api.get<ApiResponse<Attachment[]>>(`/instances/${instanceId}/attachments`),
  );
}

export async function uploadAttachment(instanceId: string, file: File): Promise<Attachment> {
  const form = new FormData();
  form.append("file", file);
  return unwrap(
    await api.post<ApiResponse<Attachment>>(`/instances/${instanceId}/attachments`, form),
  );
}

/** Bytes as something a person reads, so a 10 MB limit is legible next to a 4.2 MB file. */
export function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return "—";
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ["KB", "MB", "GB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`;
}

// ── Comments (Requirements 15.1, 15.2) ────────────────────────────────────────

/** `GET /api/instances/{id}/comments` — the thread, oldest first, flat with each reply naming its parent. */
export async function listComments(instanceId: string): Promise<Comment[]> {
  return unwrap(await api.get<ApiResponse<Comment[]>>(`/instances/${instanceId}/comments`));
}

export async function postComment(
  instanceId: string,
  body: string,
  parentId?: string | null,
): Promise<Comment> {
  return unwrap(
    await api.post<ApiResponse<Comment>>(`/instances/${instanceId}/comments`, {
      body,
      parentId: parentId ?? null,
    }),
  );
}

export function toThread(comments: Comment[]): Array<Comment & { replies: Comment[] }> {
  const byId = new Map(comments.map((comment) => [comment.id, comment]));
  const roots = comments
    .filter((comment) => !comment.parentId || !byId.has(comment.parentId))
    .map((comment) => ({ ...comment, replies: [] as Comment[] }));
  const rootsById = new Map(roots.map((root) => [root.id, root]));

  comments
    .filter((comment) => comment.parentId && byId.has(comment.parentId))
    .forEach((reply) => {
      rootsById.get(reply.parentId as string)?.replies.push(reply);
    });

  return roots;
}

// ── Delegation (Requirement 16.1) ─────────────────────────────────────────────

export async function delegateTasks(
  taskId: string,
  delegateId: string,
  startAt: string,
  endAt: string,
): Promise<Delegation> {
  return unwrap(
    await api.post<ApiResponse<Delegation>>(`/tasks/${taskId}/delegate`, {
      delegateId,
      startAt,
      endAt,
    }),
  );
}

/** A `datetime-local` value as an ISO instant, read in the reader's own zone. */
export function localDateTimeToInstant(value: string): string {
  if (!value) {
    return "";
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? "" : parsed.toISOString();
}
