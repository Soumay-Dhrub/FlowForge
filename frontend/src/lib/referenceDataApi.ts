/**
 * Typed wrappers for the reference-data lookups behind the role and department selectors.
 *
 * The user endpoints identify a role and a department by id, so the forms need a way to discover
 * those ids. `GET /api/roles` and `GET /api/departments` exist for that and are open to any
 * authenticated caller; both lists are small and stable, so they are cached for the session rather
 * than refetched per form.
 */
import api, { unwrap } from "@/lib/api";
import type { ApiResponse, DepartmentOption, RoleOption } from "@/types";

export const referenceDataKeys = {
  roles: ["roles"] as const,
  departments: ["departments"] as const,
};

/** `GET /api/roles` — selectable roles, ordered by name. */
export async function fetchRoles(): Promise<RoleOption[]> {
  return unwrap(await api.get<ApiResponse<RoleOption[]>>("/roles"));
}

/** `GET /api/departments` — selectable departments, ordered by name. */
export async function fetchDepartments(): Promise<DepartmentOption[]> {
  return unwrap(await api.get<ApiResponse<DepartmentOption[]>>("/departments"));
}
