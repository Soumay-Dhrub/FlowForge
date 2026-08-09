import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AxiosError, AxiosHeaders } from "axios";
import type { AxiosResponse } from "axios";
import LoginForm from "@/components/auth/LoginForm";
import { AuthProvider } from "@/context/AuthContext";
import * as authApi from "@/lib/authApi";
import { getAccessToken, getRefreshToken } from "@/lib/tokenStorage";
import type { User } from "@/types";

const replace = jest.fn();

jest.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: jest.fn() }),
}));

jest.mock("@/lib/authApi");

const mockedAuthApi = jest.mocked(authApi);

const adminUser: User = {
  id: "1b8f07a9-b45d-45df-9228-89c851309d89",
  name: "Platform Admin",
  email: "admin@flowforge.local",
  roleId: "role-1",
  roleName: "ADMIN",
  departmentId: "dept-1",
  departmentName: "Operations",
  isActive: true,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

/** A 401 shaped exactly like the backend's response to bad credentials. */
function invalidCredentialsError(): AxiosError {
  const response = {
    data: { success: false, message: "Invalid email or password" },
    status: 401,
    statusText: "Unauthorized",
    headers: new AxiosHeaders(),
    config: { headers: new AxiosHeaders() },
  } as AxiosResponse;
  return new AxiosError("Unauthorized", AxiosError.ERR_BAD_REQUEST, undefined, null, response);
}

function renderLoginForm() {
  return render(
    <AuthProvider>
      <LoginForm />
    </AuthProvider>,
  );
}

describe("LoginForm", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    window.localStorage.clear();
  });

  it("stores the token pair and redirects to the dashboard on a valid submit", async () => {
    mockedAuthApi.login.mockResolvedValue({
      accessToken: "access-1",
      refreshToken: "refresh-1",
      tokenType: "Bearer",
      expiresIn: 900,
    });
    mockedAuthApi.fetchCurrentUser.mockResolvedValue(adminUser);

    renderLoginForm();

    await userEvent.type(screen.getByLabelText("Email"), "admin@flowforge.local");
    await userEvent.type(screen.getByLabelText("Password"), "Admin@12345");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/dashboard"));
    expect(mockedAuthApi.login).toHaveBeenCalledWith("admin@flowforge.local", "Admin@12345");
    expect(getAccessToken()).toBe("access-1");
    expect(getRefreshToken()).toBe("refresh-1");
  });

  it("shows validation errors and does not call the API when fields are empty", async () => {
    renderLoginForm();

    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByText("Email is required")).toBeInTheDocument();
    expect(await screen.findByText("Password is required")).toBeInTheDocument();
    expect(mockedAuthApi.login).not.toHaveBeenCalled();
    expect(replace).not.toHaveBeenCalled();
    expect(screen.getByLabelText("Email")).toHaveAttribute("aria-invalid", "true");
  });

  it("surfaces the generic backend message on a 401 and stays on the page", async () => {
    mockedAuthApi.login.mockRejectedValue(invalidCredentialsError());

    renderLoginForm();

    await userEvent.type(screen.getByLabelText("Email"), "admin@flowforge.local");
    await userEvent.type(screen.getByLabelText("Password"), "wrong-password");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Invalid email or password");
    expect(replace).not.toHaveBeenCalled();
    expect(getAccessToken()).toBeNull();
  });
});
