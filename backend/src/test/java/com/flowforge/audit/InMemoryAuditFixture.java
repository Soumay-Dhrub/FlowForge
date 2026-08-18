package com.flowforge.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.flowforge.aspectfixture.ThingService;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A real {@link AuditLogAspect} applied to a real service, over an in-memory audit repository.
 *
 * <p>{@link AspectJProxyFactory} is what makes this a test of the aspect rather than of a method called
 * directly: the pointcut is matched by AspectJ against the target class exactly as Spring does at runtime,
 * so a pointcut that matches nothing fails the tests instead of quietly passing them.
 *
 * <p>{@link ThingService} is not proxied by Spring here, which means calls between its own methods would not
 * be intercepted — the same self-invocation limit the aspect documents, preserved rather than papered over.
 */
final class InMemoryAuditFixture {

    /** Every entry written, in order. */
    final List<AuditLog> entries = new ArrayList<>();

    final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    final AuditLogService auditLogService = new AuditLogService(auditLogRepository);

    /** The Spring-configured mapper's shape: dates as ISO strings, not epoch arrays. */
    final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    final AuditLogAspect aspect = new AuditLogAspect(auditLogService, objectMapper);

    InMemoryAuditFixture() {
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(call -> {
            AuditLog entry = call.getArgument(0);
            if (entry.getId() == null) {
                entry.setId(UUID.randomUUID());
                entry.setCreatedAt(Instant.now());
            }
            entries.add(entry);
            return entry;
        });
        when(auditLogRepository.findById(any(UUID.class)))
                .thenAnswer(call -> entries.stream()
                        .filter(entry -> call.<UUID>getArgument(0).equals(entry.getId()))
                        .findFirst());
        when(auditLogRepository.count()).thenAnswer(call -> (long) entries.size());
    }

    /** The fixture service behind the aspect, as Spring would wire it. */
    ThingService auditedThingService() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new ThingService(auditLogService));
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    /** Authenticate as a user, so the aspect and the service attribute entries to them. */
    static void authenticate(UUID actorId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                actorId, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    /** The entries recorded with one action. */
    List<AuditLog> withAction(String action) {
        return entries.stream().filter(entry -> action.equals(entry.getAction())).toList();
    }

    /** The entries recorded against one entity. */
    List<AuditLog> forEntity(UUID entityId) {
        return entries.stream().filter(entry -> entityId.equals(entry.getEntityId())).toList();
    }

    /** The single entry recorded, failing the caller's expectation if there is not exactly one. */
    Optional<AuditLog> onlyEntry() {
        return entries.size() == 1 ? Optional.of(entries.get(0)) : Optional.empty();
    }
}
