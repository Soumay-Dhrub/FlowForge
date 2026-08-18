package com.flowforge.aspectfixture;

import com.flowforge.audit.AuditLogService;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A service for {@code AuditLogAspect} to intercept.
 *
 * <h2>Why a fixture service and not a production one</h2>
 * <p>The aspect's job is generic: match a write method on any service, work out what it changed, record it
 * once. Testing that against {@code UserService} would test {@code UserService} — its validation, its
 * password hashing, its repositories — and would only ever exercise the one shape of method that class
 * happens to have. This fixture exists to present every shape the aspect has to cope with: a create that
 * returns a DTO, an update that returns nothing, a delete that takes only an id, a method that records its
 * own entry, a read-only method, a method with no identifiable entity, and one that throws.
 *
 * <h2>Why it lives in this package</h2>
 * <p>{@code AuditLogAspect}'s pointcut excludes {@code com.flowforge.audit..*} so that auditing does not
 * audit itself. A fixture placed beside the aspect's tests would therefore never be intercepted, and the
 * tests would pass by matching nothing. {@code com.flowforge.aspectfixture} is not a subpackage of
 * {@code com.flowforge.audit}, and the class name ends in {@code Service}, so the pointcut applies exactly
 * as it does in production.
 *
 * <p>The entity type the aspect derives from this class's name is {@code Thing}, giving actions
 * {@code CREATE_THING}, {@code UPDATE_THING}, {@code DELETE_THING}, {@code APPROVE_THING} and
 * {@code REJECT_THING}.
 */
public class ThingService {

    /** What a create returns: an id and a name, the minimum for the aspect to find an entity. */
    public record ThingResponse(UUID id, String name) {
    }

    /** A request carrying a secret, to prove the aspect never writes one to the trail. */
    public record ThingRequest(String name, String password, String apiToken) {
    }

    /** Entity type the fixture records under when it audits itself. */
    public static final String ENTITY_THING = "Thing";

    private final AuditLogService auditLogService;
    private final Set<UUID> things = new LinkedHashSet<>();

    public ThingService(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /** A create returning a DTO whose {@code id()} the aspect reads. */
    public ThingResponse createThing(String name) {
        UUID id = UUID.randomUUID();
        things.add(id);
        return new ThingResponse(id, name);
    }

    /** A create whose id the caller supplies, so a test can predict it. */
    public ThingResponse createThing(UUID id, String name) {
        things.add(id);
        return new ThingResponse(id, name);
    }

    /** A create that records its own entry — the aspect must stand down rather than add a second. */
    public ThingResponse createThingAndAuditItself(UUID id, String name) {
        things.add(id);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("id", String.valueOf(id));
        after.put("name", name);
        auditLogService.record("DOMAIN_CREATE", ENTITY_THING, id, null, after);
        return new ThingResponse(id, name);
    }

    /** An update returning nothing; the entity comes from the first UUID argument. */
    public void updateThing(UUID id, String name) {
        things.add(id);
    }

    /** An update that records its own entry with a real before/after diff. */
    public void updateThingAndAuditItself(UUID id, String name) {
        Map<String, Object> before = new LinkedHashMap<>(Map.of("name", "previous"));
        Map<String, Object> after = new LinkedHashMap<>(Map.of("name", String.valueOf(name)));
        things.add(id);
        auditLogService.record("DOMAIN_UPDATE", ENTITY_THING, id, before, after);
    }

    /** A delete taking only an id. */
    public void deleteThing(UUID id) {
        things.remove(id);
    }

    /** A delete that records its own entry. */
    public void deleteThingAndAuditItself(UUID id) {
        Map<String, Object> before = new LinkedHashMap<>(Map.of("id", String.valueOf(id)));
        things.remove(id);
        auditLogService.record("DOMAIN_DELETE", ENTITY_THING, id, before, null);
    }

    /** An approve returning a DTO. */
    public ThingResponse approveThing(UUID id) {
        return new ThingResponse(id, "approved");
    }

    /** An approve that records its own entry. */
    public ThingResponse approveThingAndAuditItself(UUID id) {
        auditLogService.record("DOMAIN_APPROVE", ENTITY_THING, id, null, Map.of("decision", "APPROVED"));
        return new ThingResponse(id, "approved");
    }

    /** A reject taking a mandatory comment. */
    public ThingResponse rejectThing(UUID id, String comment) {
        return new ThingResponse(id, "rejected");
    }

    /** A reject that records its own entry. */
    public ThingResponse rejectThingAndAuditItself(UUID id, String comment) {
        auditLogService.record("DOMAIN_REJECT", ENTITY_THING, id, null, Map.of("decision", "REJECTED"));
        return new ThingResponse(id, "rejected");
    }

    /** A create carrying secrets, to prove they are redacted rather than stored. */
    public ThingResponse createThingFromRequest(UUID id, ThingRequest request) {
        things.add(id);
        return new ThingResponse(id, request.name());
    }

    /** Matches the pointcut by name but is a query, so must not be audited. */
    @Transactional(readOnly = true)
    public ThingResponse updateNothingReadOnly(UUID id) {
        return new ThingResponse(id, "unchanged");
    }

    /** Matches the pointcut but identifies no entity, so must not be audited. */
    public void updateEverything(String note) {
        // Nothing to identify: no UUID argument and no returned id.
    }

    /** Fails, so nothing happened and nothing must be recorded. */
    public ThingResponse createThingThatFails(UUID id) {
        throw new IllegalStateException("no thing was created");
    }

    /** Does not match the pointcut at all. */
    public ThingResponse findThing(UUID id) {
        return new ThingResponse(id, "found");
    }

    /** Whether a thing is present, for tests that care that the method actually ran. */
    public boolean exists(UUID id) {
        return things.contains(id);
    }
}
