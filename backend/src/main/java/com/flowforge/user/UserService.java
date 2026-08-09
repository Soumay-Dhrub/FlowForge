package com.flowforge.user;

import com.flowforge.audit.AuditLogService;
import com.flowforge.auth.RefreshTokenRepository;
import com.flowforge.common.exception.DuplicateResourceException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.user.dto.CreateUserRequest;
import com.flowforge.user.dto.UpdateUserRequest;
import com.flowforge.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * User provisioning and profile management.
 *
 * <p>Responsibilities: unique-email enforcement, password hashing, role/department resolution,
 * account activation state, and emitting audit entries for every write. Authorization lives in
 * {@link UserController} via {@code @PreAuthorize} so the rules sit next to the endpoints they
 * guard and match the RBAC table in the design.</p>
 *
 * <p>Raw passwords are never logged and never stored: only the bcrypt hash produced by the
 * injected {@code PasswordEncoder} (configured at strength 12 in
 * {@link com.flowforge.auth.SecurityConfig}) reaches the database.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    /**
     * Create a user from a validated registration payload (Requirements 1.1, 1.2, 1.4, 1.5).
     *
     * @throws DuplicateResourceException 409 when the email is already registered
     * @throws EntityNotFoundException    404 when the role or department does not exist
     */
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        String email = normalizeEmail(request.email());

        userRepository.findByEmail(email).ifPresent(existing -> {
            throw new DuplicateResourceException("A user already exists with email: " + email);
        });

        Role role = requireRole(request.roleId());
        Department department = request.departmentId() == null ? null : requireDepartment(request.departmentId());

        User user = userMapper.toEntity(request);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(role);
        user.setDepartment(department);
        user.setIsActive(true);

        User saved = userRepository.save(user);
        auditLogService.record(
                AuditLogService.ACTION_CREATE_USER,
                AuditLogService.ENTITY_USER,
                saved.getId(),
                null,
                snapshot(saved));

        log.info("Created user {}", saved.getId());
        return userMapper.toResponse(saved);
    }

    /**
     * All users, newest first.
     */
    @Transactional(readOnly = true)
    public List<UserResponse> listUsers() {
        return userRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(userMapper::toResponse)
                .toList();
    }

    /**
     * A single user by id, regardless of activation state.
     *
     * @throws EntityNotFoundException 404 when no such user exists
     */
    @Transactional(readOnly = true)
    public UserResponse getUser(UUID userId) {
        return userMapper.toResponse(requireUser(userId));
    }

    /**
     * Apply the non-null fields of a PATCH payload. Absent fields are left untouched.
     *
     * @throws EntityNotFoundException 404 when the user, role, or department does not exist
     */
    @Transactional
    public UserResponse updateUser(UUID userId, UpdateUserRequest request) {
        User user = requireUser(userId);
        Map<String, Object> before = snapshot(user);

        userMapper.updateEntity(request, user);
        if (request.roleId() != null) {
            user.setRole(requireRole(request.roleId()));
        }
        if (request.departmentId() != null) {
            user.setDepartment(requireDepartment(request.departmentId()));
        }

        User saved = userRepository.save(user);
        auditLogService.record(
                AuditLogService.ACTION_UPDATE_USER,
                AuditLogService.ENTITY_USER,
                saved.getId(),
                before,
                snapshot(saved));

        log.info("Updated user {}", saved.getId());
        return userMapper.toResponse(saved);
    }

    /**
     * Deactivate or reactivate an account (Requirements 4.1, 4.3, 4.4).
     *
     * <p>Deactivating also revokes every live refresh token for the user, so no existing session
     * can be refreshed. Access tokens already issued stop working because
     * {@code JwtAuthenticationFilter} resolves the principal through
     * {@link UserRepository#findByIdAndIsActiveTrue(UUID)} on every request (Requirement 4.2).</p>
     *
     * @throws EntityNotFoundException 404 when no such user exists
     */
    @Transactional
    public UserResponse setAccountStatus(UUID userId, boolean active) {
        User user = requireUser(userId);
        Map<String, Object> before = snapshot(user);

        user.setIsActive(active);
        User saved = userRepository.save(user);

        if (!active) {
            int revoked = refreshTokenRepository.revokeAllByUserId(userId);
            log.info("Deactivated user {} and revoked {} refresh token(s)", userId, revoked);
        } else {
            log.info("Reactivated user {}", userId);
        }

        auditLogService.record(
                AuditLogService.ACTION_STATUS_CHANGE,
                AuditLogService.ENTITY_USER,
                userId,
                before,
                snapshot(saved));

        return userMapper.toResponse(saved);
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", userId));
    }

    private Role requireRole(UUID roleId) {
        return roleRepository.findById(roleId)
                .orElseThrow(() -> new EntityNotFoundException("Role", roleId));
    }

    private Department requireDepartment(UUID departmentId) {
        return departmentRepository.findById(departmentId)
                .orElseThrow(() -> new EntityNotFoundException("Department", departmentId));
    }

    /**
     * Audit-friendly view of a user. The password hash is deliberately excluded: audit rows are
     * read by administrators and must not become a second copy of the credential store.
     */
    private Map<String, Object> snapshot(User user) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", String.valueOf(user.getId()));
        state.put("name", user.getName());
        state.put("email", user.getEmail());
        state.put("roleId", user.getRole() == null ? null : String.valueOf(user.getRole().getId()));
        state.put("departmentId",
                user.getDepartment() == null ? null : String.valueOf(user.getDepartment().getId()));
        state.put("isActive", user.getIsActive());
        return state;
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim();
    }
}
