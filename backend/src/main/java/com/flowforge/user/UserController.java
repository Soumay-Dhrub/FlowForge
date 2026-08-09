package com.flowforge.user;

import com.flowforge.common.response.ApiResponse;
import com.flowforge.user.dto.CreateUserRequest;
import com.flowforge.user.dto.UpdateStatusRequest;
import com.flowforge.user.dto.UpdateUserRequest;
import com.flowforge.user.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.parameters.P;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * User management endpoints.
 *
 * <p>Authorization follows the RBAC table in the design document (Requirements 3.1, 3.2, 3.4):</p>
 * <ul>
 *   <li>{@code GET/POST /api/users} — ADMIN</li>
 *   <li>{@code GET/PATCH /api/users/{id}} — ADMIN, or the user themselves for their own record</li>
 *   <li>{@code PATCH /api/users/{id}/status} — ADMIN</li>
 *   <li>{@code GET /api/users/me} — any authenticated user</li>
 * </ul>
 *
 * <p>Two notes on the expressions below. First, {@code JwtAuthenticationFilter} sets the principal
 * to the caller's UUID, so {@code authentication.name} is that UUID's string form — that is what
 * the self-access checks compare against. Second, a self PATCH additionally requires
 * {@code roleId == null}: without that clause any user could grant themselves the ADMIN role
 * through their own profile endpoint.</p>
 *
 * <p>Requests with no, expired, or malformed token never reach these methods — the security filter
 * chain rejects them with 401 (Requirement 3.3).</p>
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * List all users. ADMIN only.
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<UserResponse>>> listUsers() {
        return ResponseEntity.ok(ApiResponse.success(userService.listUsers()));
    }

    /**
     * Provision a new user. ADMIN only.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> createUser(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("User created", created));
    }

    /**
     * The authenticated caller's own profile. Resolved from the security context, never from a
     * path variable, so a caller cannot ask for someone else's record here.
     */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UserResponse>> getCurrentUser(@AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(ApiResponse.success(userService.getUser(userId)));
    }

    /**
     * Fetch one user. ADMIN, or the user themselves.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or #id.toString() == authentication.name")
    public ResponseEntity<ApiResponse<UserResponse>> getUser(@P("id") @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(userService.getUser(id)));
    }

    /**
     * Update a user's profile. ADMIN, or the user themselves for non-privileged fields only.
     */
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or (#id.toString() == authentication.name and #request.roleId() == null)")
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(
            @P("id") @PathVariable UUID id,
            @P("request") @Valid @RequestBody UpdateUserRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success("User updated", userService.updateUser(id, request)));
    }

    /**
     * Activate or deactivate an account. ADMIN only. Deactivation revokes all refresh tokens.
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> setAccountStatus(
            @P("id") @PathVariable UUID id,
            @Valid @RequestBody UpdateStatusRequest request
    ) {
        UserResponse updated = userService.setAccountStatus(id, request.isActive());
        String message = request.isActive() ? "Account reactivated" : "Account deactivated";
        return ResponseEntity.ok(ApiResponse.success(message, updated));
    }
}
