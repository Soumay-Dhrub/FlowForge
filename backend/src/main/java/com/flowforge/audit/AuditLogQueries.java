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

/**
 * The filtered reads behind audit search and export (Requirements 19.3, 19.4).
 *
 * <p>Hand-built with the Criteria API rather than declared as derived finders or a JPQL string with
 * {@code (:param is null or ...)} clauses. Five optional filters are thirty-two combinations, so derived
 * finders are out; and the null-or pattern makes PostgreSQL infer the type of a bare parameter in
 * {@code ? is null}, which it cannot always do. Composing predicates only for the filters that are
 * actually set sidesteps both, and the generated SQL contains exactly the conditions asked for, which is
 * also what lets the indexes be used.
 *
 * <p>Ordering is {@code (created_at, id)} throughout — the timestamp alone is not a total order, and
 * entries written in the same transaction routinely share one. Without the id tie-break a page boundary
 * could repeat or skip an entry, which in an audit export is indistinguishable from tampering.
 */
@Component
public class AuditLogQueries {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * One page of matching entries, newest first (Requirement 19.3).
     *
     * @param filter the criteria; an empty filter matches everything
     * @param page   zero-based page index
     * @param size   page size, already clamped by the caller
     * @return the matching entries, newest first
     */
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

    /**
     * How many entries match, for the page metadata.
     *
     * @param filter the criteria
     * @return the total number of matching entries
     */
    public long count(AuditLogFilter filter) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<AuditLog> entry = query.from(AuditLog.class);

        query.select(builder.count(entry));
        query.where(predicates(builder, entry, filter, null, null).toArray(Predicate[]::new));

        return entityManager.createQuery(query).getSingleResult();
    }

    /**
     * The next chunk of an export, in {@code (created_at, id)} ascending order, strictly after a cursor
     * (Requirement 19.4).
     *
     * <p>Keyset paging, not {@code setFirstResult}. An export walks a table that other requests are
     * appending to, and offset paging over a moving table skips and repeats rows — with newest-first
     * ordering, one insert shifts every subsequent offset by one. Ascending order plus a strict
     * {@code (created_at, id) >} cursor makes each chunk depend on where the last one ended rather than
     * on how many rows exist, so no entry can be emitted twice or silently dropped. Entries appended
     * while the export runs sort after the cursor and are simply included; that is honest for an
     * append-only table and the alternative — a snapshot the client cannot see the boundary of — is not
     * more correct.
     *
     * @param filter          the criteria
     * @param afterCreatedAt  cursor timestamp, or {@code null} for the first chunk
     * @param afterId         cursor id, or {@code null} for the first chunk
     * @param limit           chunk size
     * @return the next chunk, oldest first
     */
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

    /**
     * The filter's predicates, plus the keyset cursor when one is supplied.
     *
     * <p>Only the filters that are set contribute a predicate, which is the whole point of building the
     * query here rather than writing one string with five null checks in it.
     */
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
