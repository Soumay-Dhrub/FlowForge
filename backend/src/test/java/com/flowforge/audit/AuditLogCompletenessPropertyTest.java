package com.flowforge.audit;

import com.flowforge.aspectfixture.ThingService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("flowforge")
class AuditLogCompletenessPropertyTest {

    /** The five verbs Requirement 19.1 enumerates. */
    private enum Verb {
        CREATE, UPDATE, DELETE, APPROVE, REJECT
    }

    private record Operation(Verb verb, UUID entityId, boolean auditsItself) {

        /**
         * The action the trail must end up with — literal constants, not derived from the aspect.
         */
        String expectedAction() {
            if (auditsItself) {
                return switch (verb) {
                    case CREATE -> "DOMAIN_CREATE";
                    case UPDATE -> "DOMAIN_UPDATE";
                    case DELETE -> "DOMAIN_DELETE";
                    case APPROVE -> "DOMAIN_APPROVE";
                    case REJECT -> "DOMAIN_REJECT";
                };
            }
            return switch (verb) {
                case CREATE -> "CREATE_THING";
                case UPDATE -> "UPDATE_THING";
                case DELETE -> "DELETE_THING";
                case APPROVE -> "APPROVE_THING";
                case REJECT -> "REJECT_THING";
            };
        }
    }

    @AfterTry
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Property(tries = 100)
    @Label("Property 14: every write produces exactly one audit entry, with the right actor and entity")
    void everyWriteProducesExactlyOneEntry(
            @ForAll("operations") List<Operation> operations,
            @ForAll("actors") UUID actor
    ) {
        InMemoryAuditFixture fixture = new InMemoryAuditFixture();
        ThingService things = fixture.auditedThingService();
        InMemoryAuditFixture.authenticate(actor);

        operations.forEach(operation -> apply(things, operation));

        // Exactly one entry per operation, in the order the operations ran.
        assertThat(fixture.entries)
                .as("one entry per operation, no duplicates and none missing")
                .hasSameSizeAs(operations);

        List<String> expectedActions = operations.stream().map(Operation::expectedAction).toList();
        assertThat(fixture.entries)
                .extracting(AuditLog::getAction)
                .containsExactlyElementsOf(expectedActions);

        List<UUID> expectedEntities = operations.stream().map(Operation::entityId).toList();
        assertThat(fixture.entries)
                .extracting(AuditLog::getEntityId)
                .containsExactlyElementsOf(expectedEntities);

        assertThat(fixture.entries)
                .allSatisfy(entry -> {
                    assertThat(entry.getEntityType()).isEqualTo(ThingService.ENTITY_THING);
                    assertThat(entry.getActorId()).isEqualTo(actor);
                    assertThat(entry.getCreatedAt()).isNotNull();
                });

        // And per entity: an operation on one entity does not leave a row against another.
        operations.forEach(operation -> assertThat(fixture.forEntity(operation.entityId()))
                .as("entries against entity %s", operation.entityId())
                .hasSize((int) operations.stream()
                        .filter(other -> other.entityId().equals(operation.entityId()))
                        .count()));
    }

    @Property(tries = 100)
    @Label("Property 14: repeated writes to one entity each get their own entry")
    void repeatedWritesToOneEntityAreEachRecorded(
            @ForAll("actors") UUID entityId,
            @ForAll("actors") UUID actor,
            @ForAll int repetitions
    ) {
        int times = Math.abs(repetitions % 6) + 1;

        InMemoryAuditFixture fixture = new InMemoryAuditFixture();
        ThingService things = fixture.auditedThingService();
        InMemoryAuditFixture.authenticate(actor);

        for (int i = 0; i < times; i++) {
            things.updateThing(entityId, "revision " + i);
        }

        assertThat(fixture.forEntity(entityId)).hasSize(times);
        assertThat(fixture.entries)
                .extracting(AuditLog::getAction)
                .containsOnly("UPDATE_THING");
    }

    // ── generators ───────────────────────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<List<Operation>> operations() {
        Arbitrary<Verb> verbs = Arbitraries.of(Verb.values());
        // A small pool of entity ids rather than a fresh one each time, so sequences of operations on the
        // same entity — the case where a per-entity suppression bug would hide — actually get generated.
        Arbitrary<UUID> entityIds = Arbitraries.of(entityPool());
        Arbitrary<Boolean> auditsItself = Arbitraries.of(true, false);

        return Combinators.combine(verbs, entityIds, auditsItself)
                .as(Operation::new)
                .list()
                .ofMinSize(1)
                .ofMaxSize(12);
    }

    @Provide
    Arbitrary<UUID> actors() {
        return Arbitraries.randomValue(random -> new UUID(random.nextLong(), random.nextLong()));
    }

    private static List<UUID> entityPool() {
        List<UUID> pool = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            pool.add(UUID.randomUUID());
        }
        return pool;
    }

    private void apply(ThingService things, Operation operation) {
        UUID id = operation.entityId();
        if (operation.auditsItself()) {
            switch (operation.verb()) {
                case CREATE -> things.createThingAndAuditItself(id, "a thing");
                case UPDATE -> things.updateThingAndAuditItself(id, "a thing");
                case DELETE -> things.deleteThingAndAuditItself(id);
                case APPROVE -> things.approveThingAndAuditItself(id);
                case REJECT -> things.rejectThingAndAuditItself(id, "no");
            }
            return;
        }
        switch (operation.verb()) {
            case CREATE -> things.createThing(id, "a thing");
            case UPDATE -> things.updateThing(id, "a thing");
            case DELETE -> things.deleteThing(id);
            case APPROVE -> things.approveThing(id);
            case REJECT -> things.rejectThing(id, "no");
        }
    }
}
