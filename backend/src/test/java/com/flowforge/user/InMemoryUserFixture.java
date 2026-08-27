package com.flowforge.user;

import com.flowforge.audit.AuditLog;
import com.flowforge.audit.AuditLogRepository;
import com.flowforge.audit.AuditLogService;
import com.flowforge.auth.RefreshToken;
import com.flowforge.auth.RefreshTokenRepository;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class InMemoryUserFixture {

    final Map<UUID, User> usersById = new LinkedHashMap<>();
    final Map<String, User> usersByEmail = new HashMap<>();
    final Map<UUID, Role> rolesById = new HashMap<>();
    final Map<UUID, Department> departmentsById = new HashMap<>();
    final Map<String, RefreshToken> tokensByValue = new LinkedHashMap<>();
    final List<AuditLog> auditEntries = new ArrayList<>();

    final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    final UserRepository userRepository = mock(UserRepository.class);
    final RoleRepository roleRepository = mock(RoleRepository.class);
    final DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
    final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    final AuditLogService auditLogService = new AuditLogService(auditLogRepository);
    final UserMapper userMapper = new UserMapperImpl();
    final UserService userService;

    final Role adminRole = role("ADMIN");
    final Role managerRole = role("MANAGER");
    final Role employeeRole = role("EMPLOYEE");
    final Department engineering = department("Engineering");

    InMemoryUserFixture() {
        when(userRepository.findByEmail(anyString()))
                .thenAnswer(call -> Optional.ofNullable(usersByEmail.get(call.<String>getArgument(0))));
        when(userRepository.findById(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(usersById.get(call.<UUID>getArgument(0))));
        when(userRepository.findByIdAndIsActiveTrue(any(UUID.class))).thenAnswer(call -> {
            User user = usersById.get(call.<UUID>getArgument(0));
            return Optional.ofNullable(user).filter(u -> Boolean.TRUE.equals(u.getIsActive()));
        });
        when(userRepository.findAll(any(Sort.class))).thenAnswer(call -> usersById.values().stream()
                .sorted(Comparator.comparing(User::getEmail))
                .toList());
        when(userRepository.save(any(User.class))).thenAnswer(call -> store(call.getArgument(0)));

        when(roleRepository.findById(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(rolesById.get(call.<UUID>getArgument(0))));
        when(departmentRepository.findById(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(departmentsById.get(call.<UUID>getArgument(0))));

        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(call -> {
            RefreshToken record = call.getArgument(0);
            if (record.getId() == null) {
                record.setId(UUID.randomUUID());
            }
            tokensByValue.put(record.getToken(), record);
            return record;
        });
        when(refreshTokenRepository.findByToken(anyString()))
                .thenAnswer(call -> Optional.ofNullable(tokensByValue.get(call.<String>getArgument(0))));
        when(refreshTokenRepository.findAllByUserIdAndRevokedFalse(any(UUID.class)))
                .thenAnswer(call -> liveTokensFor(call.getArgument(0)));
        when(refreshTokenRepository.revokeAllByUserId(any(UUID.class))).thenAnswer(call -> {
            List<RefreshToken> live = liveTokensFor(call.getArgument(0));
            live.forEach(token -> token.setRevoked(true));
            return live.size();
        });

        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(call -> {
            AuditLog entry = call.getArgument(0);
            if (entry.getId() == null) {
                entry.setId(UUID.randomUUID());
            }
            auditEntries.add(entry);
            return entry;
        });

        this.userService = new UserService(
                userRepository,
                roleRepository,
                departmentRepository,
                refreshTokenRepository,
                userMapper,
                passwordEncoder,
                auditLogService);
    }

    /** Register a user directly, bypassing the service, with an already-hashed password. */
    User persistUser(String name, String email, String rawPassword, Role role, boolean active) {
        User user = User.builder()
                .id(UUID.randomUUID())
                .name(name)
                .email(email)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(role)
                .department(engineering)
                .isActive(active)
                .build();
        return store(user);
    }

    /** Register a live refresh token record for a user. */
    RefreshToken persistRefreshToken(User user, String tokenValue) {
        return refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .token(tokenValue)
                .expiresAt(java.time.Instant.now().plusSeconds(3600))
                .revoked(false)
                .build());
    }

    List<AuditLog> auditEntriesWithAction(String action) {
        return auditEntries.stream().filter(entry -> action.equals(entry.getAction())).toList();
    }

    private List<RefreshToken> liveTokensFor(UUID userId) {
        return tokensByValue.values().stream()
                .filter(token -> token.getUser() != null && userId.equals(token.getUser().getId()))
                .filter(token -> !Boolean.TRUE.equals(token.getRevoked()))
                .toList();
    }

    private User store(User user) {
        if (user.getId() == null) {
            user.setId(UUID.randomUUID());
        }
        usersById.put(user.getId(), user);
        usersByEmail.put(user.getEmail(), user);
        return user;
    }

    private Role role(String name) {
        Role role = Role.builder()
                .id(UUID.randomUUID())
                .name(name)
                .permissions(new HashMap<>())
                .build();
        rolesById.put(role.getId(), role);
        return role;
    }

    private Department department(String name) {
        Department dept = Department.builder().id(UUID.randomUUID()).name(name).build();
        departmentsById.put(dept.getId(), dept);
        return dept;
    }
}
