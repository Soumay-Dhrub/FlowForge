package com.flowforge.audit;

import jakarta.persistence.Column;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AfterTry;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 15: Audit Log Immutability.
 *
 * <p><i>For any Audit_Log entry, no API endpoint or service method SHALL permit modification or deletion of
 * that entry after it is written.</i>
 *
 * <p><b>Validates: Requirements 19.2</b>
 *
 * <h2>What is checked here, and what is checked elsewhere</h2>
 * <p>The requirement is about two different surfaces, and only one of them is reachable from a unit test.
 *
 * <ul>
 *   <li><b>The Java surface — here.</b> For any entry and any mutation verb, no public operation on the
 *       audit service, its search service, its repository or its controller offers to perform that verb.
 *       This is why {@link AuditLogRepository} extends the bare {@code Repository} marker rather than
 *       {@code JpaRepository}: the latter would supply {@code delete}, {@code deleteById},
 *       {@code deleteAll} and {@code deleteAllInBatch}, and this property would fail on all four. Also
 *       checked: every persistent column is mapped {@code updatable = false}, so Hibernate cannot emit an
 *       {@code UPDATE} for an entry even if some future code changed a field on a managed instance.</li>
 *   <li><b>The database — {@code AuditLogIntegrationTest}.</b> A Java-only guard is not immutability: it
 *       binds this codebase and nothing else, and the next person to add a method can undo it. The
 *       enforcement that actually holds is the append-only trigger from
 *       {@code V4__audit_logs_append_only.sql}, and it is proved there by issuing real {@code UPDATE},
 *       {@code DELETE} and {@code TRUNCATE} statements against a real PostgreSQL and watching them be
 *       refused.</li>
 * </ul>
 *
 * <p>Both are necessary. The Java check makes the wrong thing impossible to write by accident; the database
 * check makes it impossible to do at all.
 *
 * <h2>The oracle</h2>
 * <p>Independent. The mutation verbs are a generated list of English words for changing or removing
 * something, not a list read from the code, and the field check reads JPA's own annotation rather than
 * trusting a comment. Neither asks the audit classes what they think they allow.
 */
@Tag("flowforge")
class AuditLogImmutabilityPropertyTest {

    /** Types that make up the audit trail's reachable surface. */
    private static final List<Class<?>> AUDIT_SURFACE = List.of(
            AuditLogService.class,
            AuditLogSearchService.class,
            AuditLogRepository.class,
            AuditLogController.class,
            AuditLogQueries.class);

    /**
     * Read-only operations whose names begin with a mutation verb by coincidence. Empty today, and present
     * so that adding one is a deliberate, visible decision rather than a quiet loosening of the property.
     */
    private static final List<String> PERMITTED_EXCEPTIONS = List.of();

    /** The values a generated entry is built from. */
    private record EntryValues(
            UUID actorId,
            String action,
            String entityType,
            UUID entityId,
            String stateKey,
            String stateValue
    ) {
    }

    @AfterTry
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Property(tries = 100)
    @Label("Property 15: no audit API offers to modify or delete an entry, and no column is updatable")
    void theAuditSurfaceOffersNoMutation(
            @ForAll("entries") EntryValues values,
            @ForAll("mutationVerbs") String mutationVerb
    ) {
        InMemoryAuditFixture fixture = new InMemoryAuditFixture();
        InMemoryAuditFixture.authenticate(values.actorId());

        AuditLog written = fixture.auditLogService.record(
                values.action(),
                values.entityType(),
                values.entityId(),
                null,
                state(values));

        // 1. Nothing on the reachable surface performs the verb.
        for (Class<?> type : AUDIT_SURFACE) {
            assertThat(mutatingMethods(type, mutationVerb))
                    .as("%s must expose no '%s' operation on the audit trail", type.getSimpleName(),
                            mutationVerb)
                    .isEmpty();
        }

        // 2. No persistent column can be updated, so Hibernate can never issue an UPDATE for an entry.
        assertThat(updatableColumns())
                .as("every audit column must be mapped updatable = false")
                .isEmpty();

        // 3. The entry that was written is the entry that was asked for — recording does not rewrite.
        assertThat(written.getActorId()).isEqualTo(values.actorId());
        assertThat(written.getAction()).isEqualTo(values.action());
        assertThat(written.getEntityType()).isEqualTo(values.entityType());
        assertThat(written.getEntityId()).isEqualTo(values.entityId());
        assertThat(written.getAfterState()).containsEntry(values.stateKey(), values.stateValue());
    }

    /**
     * Append-only means history accumulates. Recording the same logical change twice must leave two
     * distinct entries rather than one updated one — an audit trail that collapsed repeats would be unable
     * to show that something happened twice, which is itself a form of modification.
     */
    @Property(tries = 100)
    @Label("Property 15: recording the same change twice appends a second entry rather than replacing one")
    void recordingTwiceAppends(@ForAll("entries") EntryValues values) {
        InMemoryAuditFixture fixture = new InMemoryAuditFixture();
        InMemoryAuditFixture.authenticate(values.actorId());

        AuditLog first = fixture.auditLogService.record(
                values.action(), values.entityType(), values.entityId(), null, state(values));
        AuditLog second = fixture.auditLogService.record(
                values.action(), values.entityType(), values.entityId(), null, state(values));

        assertThat(fixture.entries).hasSize(2);
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(fixture.auditLogRepository.findById(first.getId()))
                .as("the first entry is still there, unchanged")
                .get()
                .satisfies(entry -> {
                    assertThat(entry.getAction()).isEqualTo(values.action());
                    assertThat(entry.getEntityId()).isEqualTo(values.entityId());
                });
    }

    // ── oracle helpers ───────────────────────────────────────────────────────────────────────────

    /**
     * Public methods on a type whose name begins with the verb, ignoring anything inherited from
     * {@link Object} and any documented exception.
     */
    private List<String> mutatingMethods(Class<?> type, String verb) {
        return java.util.Arrays.stream(type.getMethods())
                .filter(method -> method.getDeclaringClass() != Object.class)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(verb))
                .filter(name -> !PERMITTED_EXCEPTIONS.contains(name))
                .distinct()
                .toList();
    }

    /** Persistent columns of {@link AuditLog} that JPA would be allowed to update. */
    private List<String> updatableColumns() {
        return java.util.Arrays.stream(AuditLog.class.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Column.class))
                .filter(field -> field.getAnnotation(Column.class).updatable())
                .map(Field::getName)
                .toList();
    }

    private Map<String, Object> state(EntryValues values) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put(values.stateKey(), values.stateValue());
        return state;
    }

    // ── generators ───────────────────────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<EntryValues> entries() {
        Arbitrary<UUID> ids = Arbitraries.randomValue(
                random -> new UUID(random.nextLong(), random.nextLong()));
        Arbitrary<String> actions = Arbitraries.strings()
                .withCharRange('A', 'Z').withChars('_').ofMinLength(1).ofMaxLength(50);
        Arbitrary<String> entityTypes = Arbitraries.strings()
                .withCharRange('a', 'z').withCharRange('A', 'Z').ofMinLength(1).ofMaxLength(50);
        Arbitrary<String> keys = Arbitraries.strings()
                .withCharRange('a', 'z').ofMinLength(1).ofMaxLength(20);
        Arbitrary<String> values = Arbitraries.strings().ofMaxLength(60);

        return Combinators.combine(ids, actions, entityTypes, ids, keys, values).as(EntryValues::new);
    }

    /**
     * English verbs for changing or removing something. Written out rather than derived from the code, so
     * the property is a statement about intent instead of a restatement of what the classes happen to
     * declare.
     */
    @Provide
    Arbitrary<String> mutationVerbs() {
        return Arbitraries.of(
                "update", "delete", "remove", "modify", "edit", "set", "purge", "truncate",
                "overwrite", "erase", "drop", "clear", "replace", "amend", "revise", "patch",
                "deleteall", "deletebyid", "saveandflush", "flush");
    }
}
