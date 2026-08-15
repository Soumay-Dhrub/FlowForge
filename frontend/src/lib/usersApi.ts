/** Typed wrappers for the `/api/users` endpoints. */
import api, { unwrap } from "@/lib/api";
import type { ApiResponse, User } from "@/types";

/** Query keys, shared so a mutation invalidates exactly the caches the pages read. */
export const userKeys = {
  all: ["users"] as const,
  list: ["users", "list"] as const,
  detail: (id: string) => ["users", "detail", id] as const,
};

/** `GET /api/users` — every user, newest first. ADMIN only (403 otherwise). */
export async function fetchUsers(): Promise<User[]> {
  return unwrap(await api.get<ApiResponse<User[]>>("/users"));
}

/** `GET /api/users/{id}` — ADMIN, or the user themselves. */
export async function fetchUser(id: string): Promise<User> {
  return unwrap(await api.get<ApiResponse<User>>(`/users/${id}`));
}

export interface CreateUserInput {
  name: string;
  email: string;
  password: string;
  roleId: string;
  departmentId: string;
}

/**
 * `POST /api/users` — 201 with the created user. ADMIN only.
 *
 * A 409 means the email is already registered; callers surface that against the email field rather
 * than as a general failure, because that is the field the person has to change.
 */
export async function createUser(input: CreateUserInput): Promise<User> {
  return unwrap(await api.post<ApiResponse<User>>("/users", input));
}

export interface UpdateUserInput {
  name?: string;
  /** Omit (or send null) on a self-edit: the API refuses a self-PATCH that carries a role. */
  roleId?: string | null;
  departmentId?: string | null;
}

/**
 * `PATCH /api/users/{id}` — ADMIN, or the user themselves.
 *
 * PATCH semantics: only the fields present are applied. A self-edit must not carry `roleId` — the
 * endpoint rejects it with 403, which is what stops a user granting themselves ADMIN.
 */
export async function updateUser(id: string, input: UpdateUserInput): Promise<User> {
  return unwrap(await api.patch<ApiResponse<User>>(`/users/${id}`, input));
}

/**
 * `PATCH /api/users/{id}/status` — ADMIN only.
 *
 * Deactivating also revokes the user's refresh tokens, so their sessions stop working immediately
 * (Requirements 4.1, 4.2); reactivating lets them sign in again (Requirement 4.3).
 */
export async function setUserStatus(id: string, isActive: boolean): Promise<User> {
  return unwrap(await api.patch<ApiResponse<User>>(`/users/${id}/status`, { isActive }));
}
