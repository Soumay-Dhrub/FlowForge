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
