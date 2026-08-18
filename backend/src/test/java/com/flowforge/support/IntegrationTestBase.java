package com.flowforge.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One PostgreSQL container, shared by every integration test.
 *
 * <h2>Why the container is static and never stopped</h2>
 * <p>Each integration test class used to declare its own {@code @Container}, which JUnit starts and stops
 * per class. Seven classes meant seven container starts — most of the suite's runtime spent waiting for
 * Postgres to boot, over and over, to reach the same schema each time.
 *
 * <p>A {@code static} field started once in a static initialiser lives for the whole JVM instead. It is
 * deliberately never stopped: Testcontainers' Ryuk sidecar reaps it when the JVM exits, so an explicit
 * shutdown hook would add a way to get it wrong without adding cleanup. Spring's context cache then keeps
 * one application context alongside it, so the second class onwards costs nothing to start.
 *
 * <h2>What sharing does and does not imply</h2>
 * <p>Sharing a container means sharing a <em>database</em>, so tests are no longer isolated by
 * construction and must not assume an empty schema. That is a real constraint and the reason the existing
 * tests suit it: each seeds the rows it needs with generated ids and asserts on those rows rather than on
 * table counts. A test that needs a truly pristine database should say so by declaring its own container
 * rather than quietly making every other test depend on running first.
 *
 * <p>Flyway runs once against the shared container, which also makes this the place where a migration that
 * only works on an empty database would show up.
 */
@Tag("integration")
@SpringBootTest
public abstract class IntegrationTestBase {

    /**
     * Started once for the JVM. Not annotated {@code @Container}: that annotation is what would tie its
     * lifecycle to a single class, which is precisely what is being avoided.
     */
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("flowforge_test")
                    .withUsername("flowforge")
                    .withPassword("flowforge");

    static {
        POSTGRES.start();
    }

    /**
     * Point Spring at the shared container.
     *
     * <p>Suppliers rather than resolved strings: the registry calls them after the container is running,
     * so the mapped port is the real one rather than whatever it was before startup.
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
