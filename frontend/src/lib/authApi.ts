/** Typed wrappers for the `/api/auth` and `/api/users/me` endpoints. */
import api, { unwrap } from "@/lib/api";
import type { ApiResponse, TokenPair, User } from "@/types";

/** `POST /api/auth/login` — 401 with a generic message for bad credentials or inactive accounts. */
export async function login(email: string, password: string): Promise<TokenPair> {
  return unwrap(await api.post<ApiResponse<TokenPair>>("/auth/login", { email, password }));
}

/** `POST /api/auth/logout` — invalidates the refresh token. Idempotent. */
export async function logout(refreshToken: string): Promise<void> {
  await api.post<ApiResponse<void>>("/auth/logout", { refreshToken });
}

/** `GET /api/users/me` — the login response carries no user object, so the profile is fetched. */
export async function fetchCurrentUser(): Promise<User> {
  return unwrap(await api.get<ApiResponse<User>>("/users/me"));
}

/**
 * `POST /api/auth/password-reset/request` — always 200 with a neutral message, whether or not the
 * address is registered. The returned message is shown as-is so the UI never reveals existence.
 */
export async function requestPasswordReset(email: string): Promise<string> {
  const response = await api.post<ApiResponse<void>>("/auth/password-reset/request", { email });
  return response.data.message ?? "If that email is registered, a reset link is on its way.";
}

/** `POST /api/auth/password-reset/confirm` — 400 for unknown, expired or already-used tokens. */
export async function confirmPasswordReset(token: string, newPassword: string): Promise<void> {
  await api.post<ApiResponse<void>>("/auth/password-reset/confirm", { token, newPassword });
}
