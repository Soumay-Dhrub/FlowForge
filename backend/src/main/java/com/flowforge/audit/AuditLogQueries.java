package com.flowforge.audit;

import com.flowforge.audit.dto.AuditLogFilter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class AuditLogQueries {

    @PersistenceContext
    private EntityManager entityManager;

    public List<AuditLog> search(AuditLogFilter filter, int page, int size) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<AuditLog> query = builder.createQuery(AuditLog.class);
        Root<AuditLog> entry = query.from(AuditLog.class);

        query.where(predicates(builder, entry, filter, null, null).toArray(Predicate[]::new));
        query.orderBy(
                builder.desc(entry.get("createdAt")),
                builder.desc(entry.get("id")));

        return entityManager.createQuery(query)
                .setFirstResult(Math.max(page, 0) * size)
                .setMaxResults(size)
                .getResultList();
    }

    public long count(AuditLogFilter filter) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<AuditLog> entry = query.from(AuditLog.class);

        query.select(builder.count(entry));
        query.where(predicates(builder, entry, filter, null, null).toArray(Predicate[]::new));

        return entityManager.createQuery(query).getSingleResult();
    }

    public List<AuditLog> chunkAfter(
            AuditLogFilter filter, Instant afterCreatedAt, UUID afterId, int limit) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<AuditLog> query = builder.createQuery(AuditLog.class);
        Root<AuditLog> entry = query.from(AuditLog.class);

        query.where(predicates(builder, entry, filter, afterCreatedAt, afterId).toArray(Predicate[]::new));
        query.orderBy(
                builder.asc(entry.get("createdAt")),
                builder.asc(entry.get("id")));

        return entityManager.createQuery(query)
                .setMaxResults(limit)
                .getResultList();
    }

    private List<Predicate> predicates(
            CriteriaBuilder builder,
            Root<AuditLog> entry,
            AuditLogFilter filter,
            Instant afterCreatedAt,
            UUID afterId
    ) {
        List<Predicate> predicates = new ArrayList<>();
        if (filter != null) {
            if (filter.actorId() != null) {
                predicates.add(builder.equal(entry.get("actorId"), filter.actorId()));
            }
            if (filter.entityType() != null) {
                predicates.add(builder.equal(
                        builder.lower(entry.get("entityType")),
                        filter.entityType().toLowerCase()));
            }
            if (filter.action() != null) {
                predicates.add(builder.equal(
                        builder.lower(entry.get("action")),
                        filter.action().toLowerCase()));
            }
            if (filter.from() != null) {
                predicates.add(builder.greaterThanOrEqualTo(
                        entry.<Instant>get("createdAt"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(builder.lessThanOrEqualTo(
                        entry.<Instant>get("createdAt"), filter.to()));
            }
        }

        if (afterCreatedAt != null && afterId != null) {
            // (created_at, id) > (cursor) expressed without a row constructor, which JPQL has no
            // portable syntax for.
            predicates.add(builder.or(
                    builder.greaterThan(entry.<Instant>get("createdAt"), afterCreatedAt),
                    builder.and(
                            builder.equal(entry.get("createdAt"), afterCreatedAt),
                            builder.greaterThan(entry.<UUID>get("id"), afterId))));
        }

        return predicates;
    }
}
