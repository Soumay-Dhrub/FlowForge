import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import UserAvatar, { initialsOf } from "@/components/layout/UserAvatar";
import { AuthProvider } from "@/context/AuthContext";
import * as authApi from "@/lib/authApi";
import { setTokens } from "@/lib/tokenStorage";
import type { User } from "@/types";

const replace = jest.fn();

jest.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: jest.fn() }),
}));

jest.mock("@/lib/authApi");

const mockedAuthApi = jest.mocked(authApi);

const managerUser: User = {
  id: "1b8f07a9-b45d-45df-9228-89c851309d89",
  name: "Ada Lovelace",
  email: "ada.lovelace@flowforge.local",
  roleId: "role-2",
  roleName: "MANAGER",
  departmentId: "dept-1",
  departmentName: "Operations",
  isActive: true,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

describe("UserAvatar", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    window.localStorage.clear();
    setTokens({
      accessToken: "access-1",
      refreshToken: "refresh-1",
      tokenType: "Bearer",
      expiresIn: 900,
    });
    mockedAuthApi.fetchCurrentUser.mockResolvedValue(managerUser);
  });

  it("derives initials from the name", () => {
    expect(initialsOf("Ada Lovelace")).toBe("AL");
    expect(initialsOf("Prince")).toBe("P");
    expect(initialsOf("  ")).toBe("?");
  });

  it("shows the signed-in name and role", async () => {
    render(
      <AuthProvider>
        <UserAvatar />
      </AuthProvider>,
    );

    const trigger = await screen.findByRole("button", { name: /Ada Lovelace/ });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(trigger).toHaveTextContent("MANAGER");
  });

  it("revokes the session and redirects to login on sign out", async () => {
    mockedAuthApi.logout.mockResolvedValue(undefined);

    render(
      <AuthProvider>
        <UserAvatar />
      </AuthProvider>,
    );

    await userEvent.click(await screen.findByRole("button", { name: /Ada Lovelace/ }));
    await userEvent.click(screen.getByRole("button", { name: "Sign out" }));

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/login"));
    expect(mockedAuthApi.logout).toHaveBeenCalledWith("refresh-1");
    expect(window.localStorage.getItem("flowforge.accessToken")).toBeNull();
  });
});
