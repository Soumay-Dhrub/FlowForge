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
