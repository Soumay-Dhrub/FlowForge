package com.flowforge.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.lang.reflect.Method;
import java.time.temporal.Temporal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Audits service-layer writes that do not audit themselves (Requirement 19.1).
 *
 * <h2>What this is for, and what it is not</h2>
 * <p>It is a net, not the guarantee. Services record their own entries at the point of change, and those
 * entries are strictly better than anything an aspect can produce: they name the action in the
 * vocabulary of the domain ({@code PUBLISH_VERSION}, {@code ESCALATE_TASK}), they identify the entity
 * that actually changed, and they carry a real before/after diff because the code that changed the thing
 * had it in hand. This aspect exists so that coverage does not depend on every future author remembering
 * to add such a call.
 *
 * <h2>No duplicates</h2>
 * <p>Because both mechanisms exist, the obvious failure is two rows for one action, and a trail that says
 * everything twice is worse than one that says it once — a reviewer counting approvals would get the wrong
 * answer. So the aspect takes a reading of {@link AuditLogService#explicitWrites()} before the method runs
 * and compares it afterwards: if the invocation recorded anything explicitly, the aspect writes nothing at
 * all. The specific description wins over the generic one.
 *
 * <p>That rule is transitive, deliberately. If an intercepted method calls a service that records
 * explicitly, the outer method is not logged either. One user action produces one row describing it, not
 * one row per layer it passed through.
 *
 * <h2>Where it cannot see</h2>
 * <p>Two limits, both stated plainly because a completeness property that ignored them would be measuring
 * the wrong thing.
 * <ul>
 *   <li><b>Self-invocation is invisible.</b> Spring AOP works by proxy, so {@code this.updateFoo()} inside
 *       a service never passes through the proxy and no advice runs. An aspect therefore cannot be the
 *       source of truth for coverage of internal calls, which is the second reason the explicit calls
 *       remain.</li>
 *   <li><b>There is no before-state.</b> The advice is handed a method and its arguments, not an entity;
 *       it cannot know which row is about to change, so it cannot read it first. {@code before_state} is
 *       therefore {@code null} on aspect-written entries and {@code after_state} records what the method
 *       was asked to do and what it returned. Entries with a real diff come from the explicit calls, which
 *       is why they are not being removed.</li>
 * </ul>
 *
 * <h2>Noise</h2>
 * <p>The pointcut is broad by design — that is what makes it a net — so three things narrow it. Only
 * {@code public} methods on {@code *Service} beans under {@code com.flowforge} are matched, so helpers and
 * package-private internals are out. Read-only methods are skipped, detected by
 * {@code @Transactional(readOnly = true)} on the method or its class, which is how this codebase already
 * marks a query. And a method whose entity cannot be identified is skipped rather than logged as an
 * unattributable row: {@code audit_logs.entity_id} is {@code NOT NULL}, and an entry that cannot say what
 * it is about is noise in the strictest sense.
 *
 * <p>A method that throws is never audited. The action did not happen.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditLogAspect {

    /** Keys whose values never reach the trail, matched case-insensitively as substrings. */
    static final List<String> REDACTED_KEY_FRAGMENTS =
            List.of("password", "secret", "token", "hash", "credential");

    /** What a redacted value is replaced with. */
    static final String REDACTED = "***";

    /** Longest string value kept; longer ones are truncated with a marker. */
    static final int MAX_VALUE_LENGTH = 500;

    /** Most collection elements kept. */
    static final int MAX_COLLECTION_ELEMENTS = 20;

    /** Marks entries this aspect wrote, so an investigator can tell them from explicit ones. */
    static final String RECORDED_BY_KEY = "recordedBy";

    /** Value of {@link #RECORDED_BY_KEY} on aspect-written entries. */
    static final String RECORDED_BY = "AuditLogAspect";

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * Public write methods on FlowForge services.
     *
     * <p>The five verbs are the ones Requirement 19.1 enumerates. {@code within(com.flowforge.audit..*)}
     * is excluded because auditing the audit service would recurse.
     */
    @Pointcut("("
            + "execution(public * com.flowforge..*Service.create*(..)) || "
            + "execution(public * com.flowforge..*Service.update*(..)) || "
            + "execution(public * com.flowforge..*Service.delete*(..)) || "
            + "execution(public * com.flowforge..*Service.approve*(..)) || "
            + "execution(public * com.flowforge..*Service.reject*(..))"
            + ") && !within(com.flowforge.audit..*)")
    void serviceWrite() {
    }

    /**
     * Run the method, then record an entry if the method did not record one itself.
     *
     * @param joinPoint the intercepted invocation
     * @return whatever the method returned
     * @throws Throwable whatever the method threw, untouched — an audit concern must not change the
     *                   exception a caller sees
     */
    @Around("serviceWrite()")
    public Object auditServiceWrite(ProceedingJoinPoint joinPoint) throws Throwable {
        int writesBefore = AuditLogService.explicitWrites();

        Object result = joinPoint.proceed();

        if (AuditLogService.explicitWrites() > writesBefore) {
            // The method described itself, in its own vocabulary, with a real diff. Nothing to add.
            log.trace("{} recorded its own audit entry; aspect standing down", joinPoint.getSignature());
            return result;
        }

        AuditLog pending;
        try {
            pending = derive(joinPoint, result);
        } catch (RuntimeException failure) {
            // Deriving an entry is guesswork over reflection and serialisation; failing at it must not
            // fail an operation that already succeeded. A genuine persistence failure below is different
            // and is allowed to propagate.
            log.warn("Could not derive an audit entry for {}: {}",
                    joinPoint.getSignature(), failure.getMessage(), failure);
            return result;
        }

        if (pending != null) {
            auditLogService.record(
                    pending.getAction(),
                    pending.getEntityType(),
                    pending.getEntityId(),
                    null,
                    pending.getAfterState());
        }
        return result;
    }

    /**
     * The entry this invocation implies, or {@code null} when it should not be audited.
     *
     * <p>Returns a detached {@link AuditLog} used purely as a value carrier — it is never persisted
     * directly, so that every row still goes through {@link AuditLogService#record}.
     */
    private AuditLog derive(ProceedingJoinPoint joinPoint, Object result) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?> targetType = joinPoint.getTarget() == null
                ? signature.getDeclaringType()
                : joinPoint.getTarget().getClass();

        if (isReadOnly(targetType, signature)) {
            log.trace("{} is read-only; not audited", signature);
            return null;
        }

        Optional<UUID> entityId = entityId(result, joinPoint.getArgs());
        if (entityId.isEmpty()) {
            log.debug("No entity id derivable for {}; not audited by the aspect", signature);
            return null;
        }

        String entityType = entityType(targetType);
        return AuditLog.builder()
                .action(action(signature.getName(), entityType))
                .entityType(entityType)
                .entityId(entityId.get())
                .afterState(afterState(signature, joinPoint.getArgs(), result))
                .build();
    }

    /**
     * Whether the method is a query, judged by {@code @Transactional(readOnly = true)} on the method or
     * its class.
     *
     * <p>Not a guess about the name: {@code updateCache} and {@code createReport} both read like writes.
     * This codebase marks its queries with that annotation, so the annotation is the signal.
     */
    private boolean isReadOnly(Class<?> targetType, MethodSignature signature) {
        Transactional onMethod = null;
        try {
            Method target = targetType.getMethod(signature.getName(), signature.getParameterTypes());
            onMethod = AnnotatedElementUtils.findMergedAnnotation(target, Transactional.class);
        } catch (NoSuchMethodException unreachableThroughTargetType) {
            onMethod = AnnotatedElementUtils.findMergedAnnotation(signature.getMethod(), Transactional.class);
        }
        if (onMethod != null) {
            return onMethod.readOnly();
        }

        Transactional onClass = AnnotatedElementUtils.findMergedAnnotation(targetType, Transactional.class);
        return onClass != null && onClass.readOnly();
    }

    /**
     * The id of the thing that changed: the returned entity or DTO's id if it has one, otherwise the
     * first {@code UUID} argument.
     *
     * <p>The return value is preferred because on a create it is the only place the new id exists. The
     * argument fallback covers {@code deleteX(UUID id)} and any method whose return says nothing.
     */
    private Optional<UUID> entityId(Object result, Object[] arguments) {
        Optional<UUID> fromResult = idOf(result);
        if (fromResult.isPresent()) {
            return fromResult;
        }
        for (Object argument : arguments) {
            if (argument instanceof UUID id) {
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }

    /** A UUID id read from {@code id()} (records) or {@code getId()} (entities), if either exists. */
    private Optional<UUID> idOf(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof UUID id) {
            return Optional.of(id);
        }
        for (String accessor : List.of("id", "getId")) {
            try {
                Method method = value.getClass().getMethod(accessor);
                Object id = method.invoke(value);
                if (id instanceof UUID uuid) {
                    return Optional.of(uuid);
                }
            } catch (ReflectiveOperationException | RuntimeException noSuchAccessor) {
                // Try the next shape; a value without an id is normal, not an error.
            }
        }
        return Optional.empty();
    }

    /**
     * The entity discriminator implied by the service's name: {@code UserService} audits {@code User}.
     *
     * <p>A convention rather than a registry, because a registry would need an entry for every service
     * ever added and would be silently wrong the first time somebody forgot — whereas a service whose name
     * does not describe what it changes is visible in the trail as an odd entity type.
     */
    private String entityType(Class<?> targetType) {
        String name = targetType.getSimpleName();
        // CGLIB proxies are named Foo$$SpringCGLIB$$0; take the real class's name.
        int proxyMarker = name.indexOf("$$");
        if (proxyMarker > 0) {
            name = name.substring(0, proxyMarker);
        }
        if (name.endsWith("Service")) {
            name = name.substring(0, name.length() - "Service".length());
        }
        return name.isEmpty() ? "Unknown" : name;
    }

    /**
     * The action name: the method's verb plus the entity type, e.g. {@code UPDATE_USER}.
     *
     * <p>{@code audit_logs.action} is {@code VARCHAR(50)}, so the result is capped — a truncated action is
     * still searchable, whereas an over-long one fails the insert and loses the entry entirely.
     */
    private String action(String methodName, String entityType) {
        String verb = verbOf(methodName);
        String suffix = upperSnake(entityType);
        String action = suffix.isEmpty() ? verb : verb + "_" + suffix;
        return action.length() <= 50 ? action : action.substring(0, 50);
    }

    private String verbOf(String methodName) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        for (String verb : List.of("create", "update", "delete", "approve", "reject")) {
            if (lower.startsWith(verb)) {
                return verb.toUpperCase(Locale.ROOT);
            }
        }
        return "WRITE";
    }

    /** {@code NotificationPreference} → {@code NOTIFICATION_PREFERENCE}. */
    private String upperSnake(String camelCase) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < camelCase.length(); index++) {
            char character = camelCase.charAt(index);
            if (index > 0 && Character.isUpperCase(character)) {
                out.append('_');
            }
            out.append(Character.toUpperCase(character));
        }
        return out.toString();
    }

    /**
     * What the aspect can honestly say about the change: which method ran, what it was asked to do, and
     * what it returned.
     *
     * <p>Not "the entity's state after the change" — see the class comment for why the aspect cannot know
     * that. The {@link #RECORDED_BY_KEY} marker says so in the row itself, so nobody reads an aspect entry
     * as a diff.
     */
    private Map<String, Object> afterState(
            MethodSignature signature, Object[] arguments, Object result) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put(RECORDED_BY_KEY, RECORDED_BY);
        state.put("method", signature.getDeclaringType().getSimpleName() + "." + signature.getName());

        Map<String, Object> namedArguments = new LinkedHashMap<>();
        String[] parameterNames = signature.getParameterNames();
        for (int index = 0; index < arguments.length; index++) {
            String name = parameterNames != null && index < parameterNames.length
                    ? parameterNames[index]
                    : "arg" + index;
            namedArguments.put(name, sanitise(name, arguments[index], 0));
        }
        state.put("arguments", namedArguments);
        state.put("result", sanitise("result", result, 0));
        return state;
    }

    /**
     * A value reduced to something safe and bounded to store.
     *
     * <p>Three jobs, all of them necessary. <b>Redaction</b>: a {@code CreateUserRequest} carries a raw
     * password, and an audit trail that recorded it would be a plaintext credential store with a
     * compliance label on it. <b>Bounding</b>: an uploaded file or a workflow graph would otherwise put
     * megabytes in a JSONB column on every call. <b>Tolerance</b>: a value that will not serialise becomes
     * its type name, because an unserialisable argument is not a reason to lose the entry.
     *
     * @param name  the key this value sits under, which is what redaction matches on
     * @param value the value
     * @param depth current nesting depth, to stop a cyclic object graph
     */
    private Object sanitise(String name, Object value, int depth) {
        if (value == null) {
            return null;
        }
        if (isRedacted(name)) {
            return REDACTED;
        }
        if (depth > 3) {
            return value.getClass().getSimpleName();
        }

        if (value instanceof CharSequence text) {
            return truncate(text.toString());
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof UUID
                || value instanceof Temporal || value instanceof Enum<?>) {
            return String.valueOf(value);
        }
        if (value instanceof MultipartFile file) {
            // Never the bytes. The name and size are what an investigator needs; the content is the
            // attachment itself, which is stored and audited as its own entity.
            return Map.of("fileName", String.valueOf(file.getOriginalFilename()),
                    "size", String.valueOf(file.getSize()));
        }
        if (value instanceof byte[] bytes) {
            return "byte[" + bytes.length + "]";
        }
        if (value instanceof InputStream || value instanceof OutputStream || value instanceof Writer) {
            return value.getClass().getSimpleName();
        }

        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitised = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                String keyName = String.valueOf(key);
                sanitised.put(keyName, sanitise(keyName, nested, depth + 1));
            });
            return sanitised;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .limit(MAX_COLLECTION_ELEMENTS)
                    .map(element -> sanitise(name, element, depth + 1))
                    .toList();
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> asMap = objectMapper.convertValue(value, Map.class);
            return sanitise(name, asMap, depth);
        } catch (IllegalArgumentException notConvertible) {
            return value.getClass().getSimpleName();
        }
    }

    private boolean isRedacted(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return REDACTED_KEY_FRAGMENTS.stream().anyMatch(lower::contains);
    }

    private String truncate(String value) {
        return value.length() <= MAX_VALUE_LENGTH
                ? value
                : value.substring(0, MAX_VALUE_LENGTH) + "…(truncated)";
    }
}
