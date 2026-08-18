package com.flowforge.audit;

import com.flowforge.aspectfixture.ThingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AuditLogAspect} against a real proxied service (Requirements 19.1, 19.2).
 *
 * <p>Validates: Requirements 19.1.
 */
class AuditLogAspectTest {

    private InMemoryAuditFixture fixture;
    private ThingService things;
    private UUID actor;

    @BeforeEach
    void setUp() {
        fixture = new InMemoryAuditFixture();
        things = fixture.auditedThingService();
        actor = UUID.randomUUID();
        InMemoryAuditFixture.authenticate(actor);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Requirement 19.1: a create with no explicit audit is recorded by the aspect")
    void aCreateIsRecorded() {
        ThingService.ThingResponse created = things.createThing("Travel request");

        assertThat(fixture.entries).hasSize(1);
        AuditLog entry = fixture.entries.get(0);
        assertThat(entry.getAction()).isEqualTo("CREATE_THING");
        assertThat(entry.getEntityType()).isEqualTo("Thing");
        assertThat(entry.getEntityId()).isEqualTo(created.id());
        assertThat(entry.getActorId()).isEqualTo(actor);
        assertThat(entry.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Each verb produces its own action name")
    void everyVerbIsRecognised() {
        UUID id = UUID.randomUUID();

        things.createThing(id, "a");
        things.updateThing(id, "b");
        things.approveThing(id);
        things.rejectThing(id, "not this time");
        things.deleteThing(id);

        assertThat(fixture.entries)
                .extracting(AuditLog::getAction)
                .containsExactly(
                        "CREATE_THING", "UPDATE_THING", "APPROVE_THING", "REJECT_THING", "DELETE_THING");
        assertThat(fixture.forEntity(id)).hasSize(5);
    }

    /**
     * The rule that stops the trail saying everything twice. Services already record their own entries, so
     * an aspect that also recorded would double every action it matched.
     */
    @Test
    @DisplayName("A method that records its own entry is not recorded again")
    void anExplicitEntrySuppressesTheAspect() {
        UUID id = UUID.randomUUID();

        things.createThingAndAuditItself(id, "Travel request");

        assertThat(fixture.entries)
                .as("exactly one entry, and it is the domain's own")
                .hasSize(1);
        assertThat(fixture.entries.get(0).getAction()).isEqualTo("DOMAIN_CREATE");
        assertThat(fixture.withAction("CREATE_THING")).isEmpty();
    }

    @Test
    @DisplayName("The explicit entry is kept because it carries the before/after diff")
    void theExplicitEntryKeepsItsDiff() {
        UUID id = UUID.randomUUID();

        things.updateThingAndAuditItself(id, "revised");

        assertThat(fixture.entries).hasSize(1);
        AuditLog entry = fixture.entries.get(0);
        assertThat(entry.getAction()).isEqualTo("DOMAIN_UPDATE");
        assertThat(entry.getBeforeState()).containsEntry("name", "previous");
        assertThat(entry.getAfterState()).containsEntry("name", "revised");
    }

    @Test
    @DisplayName("A read-only method matching the pointcut by name is not audited")
    void aReadOnlyMethodIsNotAudited() {
        things.updateNothingReadOnly(UUID.randomUUID());

        assertThat(fixture.entries).isEmpty();
    }

    @Test
    @DisplayName("A method whose entity cannot be identified is skipped rather than logged unattributably")
    void aMethodWithNoEntityIsSkipped() {
        things.updateEverything("a note about nothing in particular");

        assertThat(fixture.entries).isEmpty();
    }

    @Test
    @DisplayName("A method that throws is not audited — the action did not happen")
    void aFailedMethodIsNotAudited() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> things.createThingThatFails(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("no thing was created");

        assertThat(fixture.entries).isEmpty();
    }

    @Test
    @DisplayName("A method outside the pointcut is not audited")
    void aReadMethodIsNotAudited() {
        things.findThing(UUID.randomUUID());

        assertThat(fixture.entries).isEmpty();
    }

    /**
     * The audit trail must not become a plaintext credential store. {@code CreateUserRequest} in production
     * carries a raw password for exactly this shape of call.
     */
    @Test
    @DisplayName("Secrets in arguments are redacted, never stored")
    void secretsAreRedacted() {
        UUID id = UUID.randomUUID();

        things.createThingFromRequest(id, new ThingService.ThingRequest(
                "Travel request", "correct-horse-battery-staple", "ghp_liveTokenValue"));

        AuditLog entry = fixture.entries.get(0);
        String serialised = String.valueOf(entry.getAfterState());
        assertThat(serialised)
                .doesNotContain("correct-horse-battery-staple")
                .doesNotContain("ghp_liveTokenValue")
                .contains(AuditLogAspect.REDACTED)
                .contains("Travel request");
    }

    @Test
    @DisplayName("An aspect entry says so, and carries no before-state it could not have known")
    void anAspectEntryIsMarkedAsSuch() {
        things.createThing("Travel request");

        AuditLog entry = fixture.entries.get(0);
        assertThat(entry.getAfterState())
                .containsEntry(AuditLogAspect.RECORDED_BY_KEY, AuditLogAspect.RECORDED_BY);
        assertThat(entry.getBeforeState())
                .as("the advice never saw the row before the change")
                .isNull();
    }

    @Test
    @DisplayName("A long argument is truncated rather than stored whole")
    void longValuesAreTruncated() {
        UUID id = UUID.randomUUID();
        String enormous = "x".repeat(5_000);

        things.updateThing(id, enormous);

        String serialised = String.valueOf(fixture.entries.get(0).getAfterState());
        assertThat(serialised).contains("(truncated)");
        assertThat(serialised.length()).isLessThan(2_000);
    }

    @Test
    @DisplayName("The method still returns what it would have, and still did its work")
    void theAspectDoesNotChangeBehaviour() {
        UUID id = UUID.randomUUID();

        ThingService.ThingResponse created = things.createThing(id, "Travel request");

        assertThat(created).isEqualTo(new ThingService.ThingResponse(id, "Travel request"));
        assertThat(things.exists(id)).isTrue();
    }

    @Test
    @DisplayName("With no authenticated caller the entry is recorded with no actor rather than skipped")
    void anUnauthenticatedActionIsStillRecorded() {
        SecurityContextHolder.clearContext();

        things.createThing("System bootstrap");

        assertThat(fixture.entries).hasSize(1);
        assertThat(fixture.entries.get(0).getActorId()).isNull();
        assertThat(fixture.entries.get(0).getAction()).isEqualTo("CREATE_THING");
    }

    @Test
    @DisplayName("The aspect records the arguments it was given, under their values")
    void argumentsAreRecorded() {
        UUID id = UUID.randomUUID();

        things.rejectThing(id, "insufficient documentation");

        Map<String, Object> after = fixture.entries.get(0).getAfterState();
        assertThat(String.valueOf(after)).contains("insufficient documentation");
        assertThat(String.valueOf(after)).contains(id.toString());
    }
}
