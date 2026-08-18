import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import CommentThread from "@/components/instances/CommentThread";
import * as collaborationApi from "@/lib/instanceCollaborationApi";
import { toThread } from "@/lib/instanceCollaborationApi";
import { renderWithQuery } from "@/test/renderWithQuery";
import type { Comment } from "@/types";

jest.mock("@/lib/instanceCollaborationApi", () => ({
  ...jest.requireActual("@/lib/instanceCollaborationApi"),
  listComments: jest.fn(),
  postComment: jest.fn(),
}));

const mockedApi = collaborationApi as jest.Mocked<typeof collaborationApi>;
const INSTANCE_ID = "11111111-1111-1111-1111-111111111111";

function comment(overrides: Partial<Comment> = {}): Comment {
  return {
    id: "aaaaaaaa-0000-0000-0000-000000000001",
    instanceId: INSTANCE_ID,
    authorId: "bbbbbbbb-0000-0000-0000-000000000001",
    authorName: "Ada Lovelace",
    body: "Why does this cover two quarters?",
    parentId: null,
    createdAt: "2026-01-01T10:00:00Z",
    ...overrides,
  };
}

beforeEach(() => {
  jest.clearAllMocks();
  mockedApi.listComments.mockResolvedValue([]);
});

describe("toThread", () => {
  it("groups replies under the comment they answer, keeping written order", () => {
    const parent = comment({ id: "p1" });
    const reply = comment({ id: "r1", parentId: "p1", body: "It spans both." });
    const second = comment({ id: "p2", body: "Second point." });

    const thread = toThread([parent, reply, second]);

    expect(thread.map((entry) => entry.id)).toEqual(["p1", "p2"]);
    expect(thread[0].replies.map((entry) => entry.id)).toEqual(["r1"]);
    expect(thread[1].replies).toEqual([]);
  });

  it("keeps an orphaned reply visible rather than dropping it", () => {
    // Hiding somebody's comment because its parent is missing is worse than showing it out of place.
    const orphan = comment({ id: "r1", parentId: "missing" });

    const thread = toThread([orphan]);

    expect(thread.map((entry) => entry.id)).toEqual(["r1"]);
  });
});

describe("CommentThread", () => {
  it("invites the first comment when the thread is empty", async () => {
    renderWithQuery(<CommentThread instanceId={INSTANCE_ID} />);

    expect(await screen.findByText(/No comments yet/i)).toBeInTheDocument();
  });

  it("renders a reply nested under its parent", async () => {
    mockedApi.listComments.mockResolvedValue([
      comment({ id: "p1" }),
      comment({ id: "r1", parentId: "p1", authorName: "Grace Hopper", body: "It spans both." }),
    ]);

    renderWithQuery(<CommentThread instanceId={INSTANCE_ID} />);

    expect(await screen.findByText(/Why does this cover two quarters\?/)).toBeInTheDocument();
    expect(screen.getByText("It spans both.")).toBeInTheDocument();
    expect(screen.getByText("Grace Hopper")).toBeInTheDocument();
  });

  it("posts a top-level comment with no parent", async () => {
    mockedApi.postComment.mockResolvedValue(comment());
    renderWithQuery(<CommentThread instanceId={INSTANCE_ID} />);
    await screen.findByText(/No comments yet/i);

    await userEvent.type(screen.getByLabelText(/Add a comment/i), "A new point.");
    await userEvent.click(screen.getByRole("button", { name: /Post comment/i }));

    await waitFor(() =>
      expect(mockedApi.postComment).toHaveBeenCalledWith(INSTANCE_ID, "A new point.", null),
    );
  });

  it("posts a reply against the comment being answered", async () => {
    mockedApi.listComments.mockResolvedValue([comment({ id: "p1" })]);
    mockedApi.postComment.mockResolvedValue(comment({ id: "r1", parentId: "p1" }));
    renderWithQuery(<CommentThread instanceId={INSTANCE_ID} />);

    await userEvent.click(await screen.findByRole("button", { name: /Reply/i }));
    expect(screen.getByText(/Replying to Ada Lovelace/i)).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText(/Your reply/i), "It spans both.");
    await userEvent.click(screen.getByRole("button", { name: /Post reply/i }));

    await waitFor(() =>
      expect(mockedApi.postComment).toHaveBeenCalledWith(INSTANCE_ID, "It spans both.", "p1"),
    );
  });

  it("refuses a whitespace-only comment without calling the API", async () => {
    renderWithQuery(<CommentThread instanceId={INSTANCE_ID} />);
    await screen.findByText(/No comments yet/i);

    await userEvent.type(screen.getByLabelText(/Add a comment/i), "   ");
    await userEvent.click(screen.getByRole("button", { name: /Post comment/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/Write something before posting/i);
    expect(mockedApi.postComment).not.toHaveBeenCalled();
  });

  it("lets the reviewer back out of replying", async () => {
    mockedApi.listComments.mockResolvedValue([comment({ id: "p1" })]);
    renderWithQuery(<CommentThread instanceId={INSTANCE_ID} />);

    await userEvent.click(await screen.findByRole("button", { name: /Reply/i }));
    await userEvent.click(screen.getByRole("button", { name: /Cancel/i }));

    expect(screen.queryByText(/Replying to/i)).not.toBeInTheDocument();
    expect(screen.getByLabelText(/Add a comment/i)).toBeInTheDocument();
  });
});
