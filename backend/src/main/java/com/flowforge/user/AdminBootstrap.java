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
 * Creates the first administrator when the {@code users} table is empty.
 *
 * <h2>Why this exists</h2>
 * <p>Every route to a user account required an existing account, so a fresh installation could not
 * be used at all. {@code POST /api/users} is {@code @PreAuthorize("hasRole('ADMIN')")}, there is no
 * public registration endpoint, and no migration inserts a user — {@code V2} seeds the three roles
 * and the {@code General} department and stops there. A clean database therefore came up with
 * 0 users, and the login page had nothing to accept. The application appeared to work only on
 * machines whose Docker volume still held accounts created by hand earlier.</p>
 *
 * <h2>Why a runner rather than a migration</h2>
 * <p>Putting a row in a Flyway script would bake one password hash into the repository and into
 * every deployment built from it. This instead reads the credentials from configuration, so nothing
 * secret is committed, and each environment decides its own.</p>
 *
 * <h2>Safety</h2>
 * <ul>
 *   <li><b>Idempotent.</b> It runs only when the table holds no users at all, so it cannot alter an
 *       existing installation, and restarting never re-creates or resets an account. Deleting the
 *       last user re-enables it, which is the desired recovery behaviour.</li>
 *   <li><b>No default password.</b> With {@code app.bootstrap.admin.password} unset a strong random
 *       one is generated and written to the log once. That is deliberate: a committed default such
 *       as "admin" would ship a known credential to every deployment. Same trade-off Spring Boot
 *       itself makes with its generated security password.</li>
 *   <li><b>Disableable.</b> Set {@code app.bootstrap.admin.enabled=false}
 *       ({@code BOOTSTRAP_ADMIN_ENABLED}) where accounts are provisioned another way.</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "app.bootstrap.admin.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap {

    /** Role seeded by {@code V2__seed_roles_and_departments.sql} that carries {@code manageUsers}. */
    private static final String ADMIN_ROLE = "ADMIN";

    /** Department seeded by {@code V2__seed_roles_and_departments.sql}. */
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
            // Normal path for every run after the first. Silent by design: an installation with
            // users does not need to be told about a step that does not apply to it.
            return;
        }

        var role = roleRepository.findByName(ADMIN_ROLE).orElse(null);
        var department = departmentRepository.findByName(DEFAULT_DEPARTMENT).orElse(null);
        if (role == null || department == null) {
            // Only reachable if the V2 seed data was removed. Warn rather than throw: refusing to
            // start would turn a missing convenience into a total outage.
            log.warn("Cannot create the first administrator: role '{}' or department '{}' is missing. "
                            + "Check that V2__seed_roles_and_departments.sql has been applied.",
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
            // The only time a credential is ever logged, and the only way the operator can learn
            // it. Printed once, on an otherwise unusable installation.
            log.warn("""

                            ════════════════════════════════════════════════════════════════════
                             FlowForge had no users, so a first administrator was created.
                               email:    {}
                               password: {}
                             Generated because app.bootstrap.admin.password was not set. It is not
                             stored anywhere in plain text and will not be shown again — sign in
                             and change it, or set BOOTSTRAP_ADMIN_PASSWORD and start from a clean
                             database.
                            ════════════════════════════════════════════════════════════════════""",
                    email, password);
        } else {
            log.info("FlowForge had no users, so a first administrator was created for {} "
                    + "using the configured password.", email);
        }
    }

    /** 24 random bytes, URL-safe. Long enough that the generated account is not worth attacking. */
    private String generatePassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
