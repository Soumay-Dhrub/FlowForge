package com.flowforge.user;

import com.flowforge.common.response.ApiResponse;
import com.flowforge.user.dto.RoleResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Role lookup endpoint.
 *
 * <p>{@code GET /api/roles} — any authenticated caller. Creating or editing a user requires a
 * {@code roleId}, and without this endpoint a client would have to hard-code the seeded UUIDs.
 * Provisioning itself stays ADMIN-only in {@link UserController}; reading the list of role names
 * grants nothing, which is why the bar here is only authentication.</p>
 *
 * <p>Read-only by design: roles and their permission sets are defined by migration, not by API.</p>
 */
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final ReferenceDataService referenceDataService;

    /**
     * List all roles, ordered by name.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<RoleResponse>>> listRoles() {
        return ResponseEntity.ok(ApiResponse.success(referenceDataService.listRoles()));
    }
}
