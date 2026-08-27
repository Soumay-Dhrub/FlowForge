package com.flowforge.user;

import com.flowforge.user.dto.DepartmentResponse;
import com.flowforge.user.dto.RoleResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
