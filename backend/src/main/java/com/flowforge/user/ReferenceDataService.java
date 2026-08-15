package com.flowforge.user;

import com.flowforge.user.dto.DepartmentResponse;
import com.flowforge.user.dto.RoleResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-only lookups for the reference data the user forms need.
 *
 * <p>{@code CreateUserRequest} and {@code UpdateUserRequest} identify a role and a department by id,
 * so any client that provisions or edits a user has to be able to discover those ids. This service
 * backs {@link RoleController} and {@link DepartmentController}, which exist for exactly that.</p>
 *
 * <p>Both lists are sorted by name so the option order a user sees is stable between requests.
 * Neither collection is paged: roles are the three seeded by {@code V2__seed_roles_and_departments}
 * and departments are organizational units, not a growing transactional table.</p>
 */
@Service
@RequiredArgsConstructor
public class ReferenceDataService {

    private static final Sort BY_NAME = Sort.by(Sort.Direction.ASC, "name");

    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;

    /** All roles, by name. */
    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles() {
        return roleRepository.findAll(BY_NAME).stream()
                .map(role -> new RoleResponse(role.getId(), role.getName()))
                .toList();
    }

    /** All departments, by name. */
    @Transactional(readOnly = true)
    public List<DepartmentResponse> listDepartments() {
        return departmentRepository.findAll(BY_NAME).stream()
                .map(department -> new DepartmentResponse(department.getId(), department.getName()))
                .toList();
    }
}
