package com.flowforge.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Creates the first administrator while the users table is empty, since creating a user otherwise
 * requires an existing ADMIN and there is no public registration.
 *
 * <p>Runs only when no users exist, so it cannot alter an existing installation. The password is
 * generated and logged once when unset rather than defaulted, because a committed default would be a
 * known credential in every deployment.
 */
@Configuration
@ConditionalOnProperty(name = "app.bootstrap.admin.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap {

    private static final String ADMIN_ROLE = "ADMIN";
    private static final String DEFAULT_DEPARTMENT = "General";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final PasswordEncoder passwordEncoder;

    @Bean
    ApplicationRunner seedFirstAdmin(
            @Value("${app.bootstrap.admin.email:admin@flowforge.local}") String email,
            @Value("${app.bootstrap.admin.password:}") String configuredPassword
    ) {
        return args -> createFirstAdmin(email, configuredPassword);
    }

    @Transactional
    void createFirstAdmin(String email, String configuredPassword) {
        if (userRepository.count() > 0) {
            return;
        }

        var role = roleRepository.findByName(ADMIN_ROLE).orElse(null);
        var department = departmentRepository.findByName(DEFAULT_DEPARTMENT).orElse(null);
        if (role == null || department == null) {
            // Only reachable if the V2 seed data was removed. Warn rather than throw: refusing to
            // start would turn a missing convenience into an outage.
            log.warn("Cannot create the first administrator: role '{}' or department '{}' is missing",
                    ADMIN_ROLE, DEFAULT_DEPARTMENT);
            return;
        }

        boolean generated = configuredPassword.isBlank();
        String password = generated ? generatePassword() : configuredPassword;

        userRepository.save(User.builder()
                .name("Administrator")
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .role(role)
                .department(department)
                .isActive(true)
                .build());

        if (generated) {
            log.warn("""

                            ════════════════════════════════════════════════════════════════════
                             FlowForge had no users, so a first administrator was created.
                               email:    {}
                               password: {}
                             Generated because app.bootstrap.admin.password was not set. It is not
                             stored in plain text and will not be shown again — sign in and change
                             it, or set BOOTSTRAP_ADMIN_PASSWORD and start from a clean database.
                            ════════════════════════════════════════════════════════════════════""",
                    email, password);
        } else {
            log.info("FlowForge had no users, so a first administrator was created for {}", email);
        }
    }

    private String generatePassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
