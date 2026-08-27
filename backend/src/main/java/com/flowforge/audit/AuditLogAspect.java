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
 * Audits service writes that do not audit themselves. A net, not the guarantee.
 *
 * <p>Skips the entry entirely when the invocation already recorded one explicitly, so one action
 * does not produce two rows. Two known blind spots: self-invocation is invisible because Spring AOP
 * is proxy-based, and there is no before-state, so before_state is null on aspect-written entries.
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

    @Pointcut("("
            + "execution(public * com.flowforge..*Service.create*(..)) || "
            + "execution(public * com.flowforge..*Service.update*(..)) || "
            + "execution(public * com.flowforge..*Service.delete*(..)) || "
            + "execution(public * com.flowforge..*Service.approve*(..)) || "
            + "execution(public * com.flowforge..*Service.reject*(..))"
            + ") && !within(com.flowforge.audit..*)")
    void serviceWrite() {
    }

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
