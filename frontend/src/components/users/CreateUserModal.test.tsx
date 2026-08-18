import { useState } from "react";
import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AxiosError, AxiosHeaders } from "axios";
import CreateUserModal from "@/components/users/CreateUserModal";
import * as referenceDataApi from "@/lib/referenceDataApi";
import * as usersApi from "@/lib/usersApi";
import type { User } from "@/types";
import { createTestQueryClient } from "@/test/renderWithQuery";

jest.mock("@/lib/usersApi", () => ({
  ...jest.requireActual("@/lib/usersApi"),
  createUser: jest.fn(),
}));
jest.mock("@/lib/referenceDataApi", () => ({
  ...jest.requireActual("@/lib/referenceDataApi"),
  fetchRoles: jest.fn(),
  fetchDepartments: jest.fn(),
}));

const mockedUsersApi = jest.mocked(usersApi);
const mockedReferenceDataApi = jest.mocked(referenceDataApi);

const ROLE_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const DEPARTMENT_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

function created(): User {
  return {
    id: "cccccccc-cccc-cccc-cccc-cccccccccccc",
    name: "Grace Hopper",
    email: "grace@flowforge.local",
    roleId: ROLE_ID,
    roleName: "MANAGER",
    departmentId: DEPARTMENT_ID,
    departmentName: "Operations",
    isActive: true,
    createdAt: "2026-01-01T10:00:00Z",
    updatedAt: "2026-01-01T10:00:00Z",
  };
}

function conflict(message: string): AxiosError {
  const error = new AxiosError("Request failed with status code 409");
  error.response = {
    status: 409,
    statusText: "Conflict",
    data: { success: false, message },
    headers: {},
    config: { headers: new AxiosHeaders() },
  };
  return error;
}

function renderModal(onClose = jest.fn()) {
  const queryClient = createTestQueryClient();
  const result = render(
    <QueryClientProvider client={queryClient}>
      <CreateUserModal open onClose={onClose} />
    </QueryClientProvider>,
  );
  return { ...result, onClose };
}

async function fillValidForm() {
  await userEvent.type(screen.getByLabelText("Name"), "Grace Hopper");
  await userEvent.type(screen.getByLabelText("Email"), "grace@flowforge.local");
  await userEvent.type(screen.getByLabelText("Temporary password"), "Sup3rSecret!");
  await userEvent.selectOptions(screen.getByLabelText("Role"), ROLE_ID);
  await userEvent.selectOptions(screen.getByLabelText("Department"), DEPARTMENT_ID);
}

describe("CreateUserModal", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockedReferenceDataApi.fetchRoles.mockResolvedValue([{ id: ROLE_ID, name: "MANAGER" }]);
    mockedReferenceDataApi.fetchDepartments.mockResolvedValue([
      { id: DEPARTMENT_ID, name: "Operations" },
    ]);
  });

  it("offers the roles and departments the API returns rather than hard-coded ids", async () => {
    renderModal();

    expect(
      await screen.findByRole("option", { name: "MANAGER", hidden: false }),
    ).toHaveValue(ROLE_ID);
    expect(screen.getByRole("option", { name: "Operations" })).toHaveValue(DEPARTMENT_ID);
  });

  it("refuses an empty submission and names every missing field", async () => {
    renderModal();
    await screen.findByRole("option", { name: "MANAGER" });

    await userEvent.click(screen.getByRole("button", { name: "Create user" }));

    expect(await screen.findByText("Name is required")).toBeInTheDocument();
    expect(screen.getByText("Email is required")).toBeInTheDocument();
    expect(screen.getByText("Password must be at least 8 characters")).toBeInTheDocument();
    // The selectors' messages repeat their placeholder text, so assert the invalid state itself.
    expect(screen.getByLabelText("Role")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByLabelText("Department")).toHaveAttribute("aria-invalid", "true");
    expect(mockedUsersApi.createUser).not.toHaveBeenCalled();
    expect(screen.getByLabelText("Name")).toHaveAttribute("aria-invalid", "true");
  });

  it("rejects a malformed email and a short password before calling the API", async () => {
    renderModal();
    await screen.findByRole("option", { name: "MANAGER" });

    await userEvent.type(screen.getByLabelText("Name"), "Grace Hopper");
    await userEvent.type(screen.getByLabelText("Email"), "not-an-email");
    await userEvent.type(screen.getByLabelText("Temporary password"), "short");
    await userEvent.selectOptions(screen.getByLabelText("Role"), ROLE_ID);
    await userEvent.selectOptions(screen.getByLabelText("Department"), DEPARTMENT_ID);
    await userEvent.click(screen.getByRole("button", { name: "Create user" }));

    expect(await screen.findByText("Enter a valid email address")).toBeInTheDocument();
    expect(screen.getByText("Password must be at least 8 characters")).toBeInTheDocument();
    expect(mockedUsersApi.createUser).not.toHaveBeenCalled();
  });

  it("submits a valid payload and closes", async () => {
    mockedUsersApi.createUser.mockResolvedValue(created());
    const { onClose } = renderModal();
    await screen.findByRole("option", { name: "MANAGER" });

    await fillValidForm();
    await userEvent.click(screen.getByRole("button", { name: "Create user" }));

    await waitFor(() =>
      expect(mockedUsersApi.createUser).toHaveBeenCalledWith({
        name: "Grace Hopper",
        email: "grace@flowforge.local",
        password: "Sup3rSecret!",
        roleId: ROLE_ID,
        departmentId: DEPARTMENT_ID,
      }),
    );
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it("reports a duplicate email against the email field, not as a generic failure", async () => {
    mockedUsersApi.createUser.mockRejectedValue(
      conflict("A user already exists with email: grace@flowforge.local"),
    );
    renderModal();
    await screen.findByRole("option", { name: "MANAGER" });

    await fillValidForm();
    await userEvent.click(screen.getByRole("button", { name: "Create user" }));

    const email = screen.getByLabelText("Email");
    await waitFor(() => expect(email).toHaveAttribute("aria-invalid", "true"));
    const message = await screen.findByText("A user already exists with email: grace@flowforge.local");
    // The message is programmatically associated with the field it belongs to.
    expect(email.getAttribute("aria-describedby")).toContain(message.id);
  });

  it("traps focus, closes on Escape and returns focus to the trigger", async () => {
    const onClose = jest.fn();

    function Harness() {
      const [open, setOpen] = useState(false);
      return (
        <>
          <button type="button" onClick={() => setOpen(true)}>
            New user
          </button>
          <CreateUserModal
            open={open}
            onClose={() => {
              onClose();
              setOpen(false);
            }}
          />
        </>
      );
    }

    const queryClient = createTestQueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <Harness />
      </QueryClientProvider>,
    );

    const trigger = screen.getByRole("button", { name: "New user" });
    await userEvent.click(trigger);

    const dialog = await screen.findByRole("dialog", { name: "New user" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    // Focus starts inside the dialog and Tab cycles without leaving it.
    expect(dialog).toContainElement(document.activeElement as HTMLElement);
    for (let i = 0; i < 8; i += 1) {
      await userEvent.tab();
      expect(dialog).toContainElement(document.activeElement as HTMLElement);
    }

    await userEvent.keyboard("{Escape}");

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();
  });
});
