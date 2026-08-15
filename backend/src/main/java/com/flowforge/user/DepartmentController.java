package com.flowforge.user;

import com.flowforge.common.response.ApiResponse;
import com.flowforge.user.dto.DepartmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Department lookup endpoint.
 *
 * <p>{@code GET /api/departments} — any authenticated caller, for the same reason as
 * {@link RoleController}: user creation and profile edits carry a {@code departmentId}, so the
 * options have to be discoverable. Only id and name are returned, so no user's identity leaks
 * through the department's manager.</p>
 *
 * <p>Read-only by design: departments are seeded and administered outside this API.</p>
 */
@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final ReferenceDataService referenceDataService;

    /**
     * List all departments, ordered by name.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<DepartmentResponse>>> listDepartments() {
        return ResponseEntity.ok(ApiResponse.success(referenceDataService.listDepartments()));
    }
}
