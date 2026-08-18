import { fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import AttachmentPanel from "@/components/instances/AttachmentPanel";
import * as collaborationApi from "@/lib/instanceCollaborationApi";
import { renderWithQuery } from "@/test/renderWithQuery";
import type { Attachment } from "@/types";

jest.mock("@/lib/instanceCollaborationApi", () => ({
  ...jest.requireActual("@/lib/instanceCollaborationApi"),
  listAttachments: jest.fn(),
  uploadAttachment: jest.fn(),
}));

const mockedApi = collaborationApi as jest.Mocked<typeof collaborationApi>;
const INSTANCE_ID = "11111111-1111-1111-1111-111111111111";

function attachment(overrides: Partial<Attachment> = {}): Attachment {
  return {
    id: "22222222-2222-2222-2222-222222222222",
    instanceId: INSTANCE_ID,
    fileName: "receipt.pdf",
    contentType: "application/pdf",
    fileSize: 2048,
    uploadedById: "33333333-3333-3333-3333-333333333333",
    createdAt: "2026-01-01T10:00:00Z",
    ...overrides,
  };
}

/** A File the size the test wants, without allocating the bytes. */
function fileOfSize(name: string, type: string, bytes: number): File {
  const file = new File(["x"], name, { type });
  Object.defineProperty(file, "size", { value: bytes });
  return file;
}

beforeEach(() => {
  jest.clearAllMocks();
  mockedApi.listAttachments.mockResolvedValue([]);
});

describe("AttachmentPanel", () => {
  it("states the limits rather than leaving them to be discovered by failing", async () => {
    renderWithQuery(<AttachmentPanel instanceId={INSTANCE_ID} />);

    expect(await screen.findByText(/Up to 10 MB per file/i)).toBeInTheDocument();
    expect(screen.getByText(/PDF, JPEG, PNG, DOC, DOCX/)).toBeInTheDocument();
  });

  it("lists what is already attached, with a readable size", async () => {
    mockedApi.listAttachments.mockResolvedValue([attachment({ fileSize: 4_404_019 })]);

    renderWithQuery(<AttachmentPanel instanceId={INSTANCE_ID} />);

    expect(await screen.findByText("receipt.pdf")).toBeInTheDocument();
    expect(screen.getByText("4.2 MB")).toBeInTheDocument();
  });

  it("says so plainly when nothing is attached", async () => {
    renderWithQuery(<AttachmentPanel instanceId={INSTANCE_ID} />);

    expect(await screen.findByText(/Nothing attached to this request yet/i)).toBeInTheDocument();
  });

  it("uploads an allowed file and refreshes the list", async () => {
    mockedApi.uploadAttachment.mockResolvedValue(attachment());
    renderWithQuery(<AttachmentPanel instanceId={INSTANCE_ID} />);
    await screen.findByText(/Nothing attached/i);

    await userEvent.upload(
      screen.getByLabelText(/Attach a file/i),
      fileOfSize("receipt.pdf", "application/pdf", 2048),
    );

    await waitFor(() => expect(mockedApi.uploadAttachment).toHaveBeenCalledTimes(1));
    expect(mockedApi.uploadAttachment.mock.calls[0][0]).toBe(INSTANCE_ID);
    expect(await screen.findByText(/Attached receipt.pdf/i)).toBeInTheDocument();
  });

  it("refuses an oversized file without spending the upload", async () => {
    renderWithQuery(<AttachmentPanel instanceId={INSTANCE_ID} />);
    await screen.findByText(/Nothing attached/i);

    await userEvent.upload(
      screen.getByLabelText(/Attach a file/i),
      fileOfSize("huge.pdf", "application/pdf", 11 * 1024 * 1024),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(/the limit is 10 MB/i);
    expect(mockedApi.uploadAttachment).not.toHaveBeenCalled();
  });

  it("refuses a disallowed type without spending the upload", async () => {
    renderWithQuery(<AttachmentPanel instanceId={INSTANCE_ID} />);
    await screen.findByText(/Nothing attached/i);

    // Deliberately a raw change event rather than userEvent.upload, which honours the input's `accept`
    // attribute and would silently drop this file — leaving the component's own check unexercised. A
    // real user can get past `accept` by choosing "All files" or by dropping a file, which is exactly
    // why the check exists and why it has to be tested this way.
    const input = screen.getByLabelText(/Attach a file/i) as HTMLInputElement;
    fireEvent.change(input, { target: { files: [fileOfSize("notes.txt", "text/plain", 128)] } });

    expect(await screen.findByRole("alert")).toHaveTextContent(/not an allowed type/i);
    expect(mockedApi.uploadAttachment).not.toHaveBeenCalled();
  });

  it("surfaces the server's refusal when it rejects an upload the client allowed", async () => {
    // The client's checks are a courtesy; the server is the authority and may still say no.
    mockedApi.uploadAttachment.mockRejectedValue(new Error("Attachment storage is not writable"));
    renderWithQuery(<AttachmentPanel instanceId={INSTANCE_ID} />);
    await screen.findByText(/Nothing attached/i);

    await userEvent.upload(
      screen.getByLabelText(/Attach a file/i),
      fileOfSize("receipt.pdf", "application/pdf", 2048),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(/Could not upload that file/i);
  });
});
