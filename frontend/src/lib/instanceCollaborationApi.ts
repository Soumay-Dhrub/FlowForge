/**
 * Attachments, comments and delegation — the three things a reviewer does on a request besides
 * deciding it (Requirements 14.1, 15.1, 15.2, 16.1).
 *
 * Grouped in one module because they share an access rule rather than a resource: all three are
 * restricted to participants of the instance, and all three answer 403 to anyone else. Splitting them
 * across three files would spread that one fact thinly.
 */
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

/**
 * `POST /api/instances/{id}/attachments` — upload one file.
 *
 * The `Content-Type` header is deliberately left unset: the browser has to write it, because only it
 * knows the multipart boundary. Setting `multipart/form-data` by hand omits that boundary and the
 * request is rejected as malformed.
 *
 * The server is the authority on size and type — it counts bytes as it writes and checks the type
 * against its own allow-list — so a client-side check is a courtesy that saves an upload, never the
 * gate.
 */
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

/**
 * `POST /api/instances/{id}/comments` — post a comment, or a reply when `parentId` is given.
 *
 * Replying to a reply, or to a comment on another request, is refused with 400 by the server; the UI
 * only ever offers Reply on a top-level comment, so that should not be reachable from here.
 */
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

/**
 * A flat thread arranged for rendering: top-level comments in order, each with its replies.
 *
 * The server returns one ordered list precisely so the client can do this without losing position
 * information. A reply whose parent is missing from the list is kept as top-level rather than dropped —
 * silently hiding somebody's comment is worse than showing it slightly out of place.
 */
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

/**
 * `POST /api/tasks/{id}/delegate` — hand this reviewer's pending work to somebody else for a window.
 *
 * The path names a task but the effect is per-user: every pending task the caller holds moves, and new
 * work routes to the delegate while the window is open. The response says how many changed hands, which
 * is what the UI reports back — "delegated" without a count leaves the reviewer guessing.
 */
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
