"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, MessageSquare } from "lucide-react";
import { extractErrorMessage } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import {
  collaborationKeys,
  listComments,
  postComment,
  toThread,
} from "@/lib/instanceCollaborationApi";
import SubmitButton from "@/components/ui/SubmitButton";
import type { Comment } from "@/types";

const BODY_MAX = 5000;

export function CommentThread({ instanceId }: { instanceId: string }) {
  const queryClient = useQueryClient();
  const [body, setBody] = useState("");
  const [replyTo, setReplyTo] = useState<Comment | null>(null);
  const [error, setError] = useState<string | null>(null);

  const comments = useQuery({
    queryKey: collaborationKeys.comments(instanceId),
    queryFn: () => listComments(instanceId),
  });

  const post = useMutation({
    mutationFn: () => postComment(instanceId, body, replyTo?.id ?? null),
    onSuccess: () => {
      setBody("");
      setReplyTo(null);
      queryClient.invalidateQueries({ queryKey: collaborationKeys.comments(instanceId) });
    },
  });

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);

    // Whitespace is not a comment. The server refuses it too; checking here means the reviewer is told
    // without a round trip.
    if (!body.trim()) {
      setError("Write something before posting.");
      return;
    }

    try {
      await post.mutateAsync();
    } catch (failure) {
      setError(extractErrorMessage(failure, "Could not post that comment."));
    }
  }

  const thread = toThread(comments.data ?? []);

  return (
    <section className="mt-8">
      <h2 className="text-lg font-semibold text-gray-900">Discussion</h2>

      {comments.isPending ? (
        <p role="status" className="mt-2 inline-flex items-center gap-2 text-sm text-gray-600">
          <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
          Loading the discussion…
        </p>
      ) : null}

      {comments.isError ? (
        <p role="alert" className="mt-2 text-sm text-danger-700">
          {extractErrorMessage(comments.error, "Could not load the discussion.")}
        </p>
      ) : null}

      {comments.isSuccess && thread.length === 0 ? (
        <p className="mt-2 text-sm text-gray-600">
          No comments yet. Ask a question or add context for whoever decides this.
        </p>
      ) : null}

      {thread.length > 0 ? (
        <ol className="mt-3 space-y-4">
          {thread.map((comment) => (
            <li key={comment.id}>
              <CommentCard comment={comment} />

              {comment.replies.length > 0 ? (
                <ol className="mt-2 space-y-2 border-l-2 border-gray-200 pl-4">
                  {comment.replies.map((reply) => (
                    <li key={reply.id}>
                      <CommentCard comment={reply} />
                    </li>
                  ))}
                </ol>
              ) : null}

              <button
                type="button"
                onClick={() => {
                  setReplyTo(comment);
                  setError(null);
                }}
                className="mt-2 inline-flex items-center gap-1 text-xs font-medium text-primary-700 hover:underline"
              >
                <MessageSquare aria-hidden="true" className="h-3 w-3" />
                Reply
              </button>
            </li>
          ))}
        </ol>
      ) : null}

      <form onSubmit={onSubmit} noValidate className="mt-4 space-y-2">
        {replyTo ? (
          <p className="rounded-md bg-gray-50 px-3 py-2 text-xs text-gray-700">
            Replying to {replyTo.authorName ?? "a comment"}:{" "}
            <span className="italic">{truncate(replyTo.body)}</span>
            <button
              type="button"
              onClick={() => setReplyTo(null)}
              className="ml-2 font-medium text-primary-700 hover:underline"
            >
              Cancel
            </button>
          </p>
        ) : null}

        <label htmlFor="comment-body" className="block text-sm font-medium text-gray-700">
          {replyTo ? "Your reply" : "Add a comment"}
        </label>
        <textarea
          id="comment-body"
          rows={3}
          maxLength={BODY_MAX}
          value={body}
          onChange={(event) => setBody(event.target.value)}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? "comment-error" : undefined}
          className={`w-full rounded-md border px-3 py-2 text-gray-900 shadow-sm outline-none focus:ring-2 focus:ring-primary-500 ${
            error ? "border-danger-600" : "border-gray-300"
          }`}
        />
        {error ? (
          <p id="comment-error" role="alert" className="text-sm text-danger-700">
            {error}
          </p>
        ) : null}

        <SubmitButton isSubmitting={post.isPending} pendingLabel="Posting…">
          {replyTo ? "Post reply" : "Post comment"}
        </SubmitButton>
      </form>
    </section>
  );
}

function CommentCard({ comment }: { comment: Comment }) {
  return (
    <article className="rounded-xl border border-gray-200 bg-white px-4 py-3 text-sm">
      <header className="flex flex-wrap items-baseline gap-x-2">
        <span className="font-medium text-gray-900">{comment.authorName ?? "Unknown author"}</span>
        <time dateTime={comment.createdAt} className="text-xs text-gray-500">
          {formatDateTime(comment.createdAt)}
        </time>
      </header>
      <p className="mt-1 whitespace-pre-wrap text-gray-800">{comment.body}</p>
    </article>
  );
}

function truncate(text: string): string {
  return text.length > 60 ? `${text.slice(0, 60)}…` : text;
}

export default CommentThread;
