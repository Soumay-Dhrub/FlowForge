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

export async function createUser(input: CreateUserInput): Promise<User> {
  return unwrap(await api.post<ApiResponse<User>>("/users", input));
}

export interface UpdateUserInput {
  name?: string;
  /** Omit (or send null) on a self-edit: the API refuses a self-PATCH that carries a role. */
  roleId?: string | null;
  departmentId?: string | null;
}

export async function updateUser(id: string, input: UpdateUserInput): Promise<User> {
  return unwrap(await api.patch<ApiResponse<User>>(`/users/${id}`, input));
}

export async function setUserStatus(id: string, isActive: boolean): Promise<User> {
  return unwrap(await api.patch<ApiResponse<User>>(`/users/${id}/status`, { isActive }));
}
