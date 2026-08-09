package com.flowforge.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable record of a create, update, approve, reject, or delete action on any entity.
 *
 * <p>Maps to the {@code audit_logs} table created in {@code V1__initial_schema.sql}. Every
 * column is marked {@code updatable = false}: once a row is written, JPA will never issue an
 * UPDATE for it, which is the persistence-layer half of the immutability guarantee
 * (Requirement 19.2). There is deliberately no {@code updated_at} column and no soft-delete flag.</p>
 *
 * <p>{@code actorId} is a plain UUID rather than a {@code @ManyToOne} association: the audit
 * trail must survive actor deletion (the FK is {@code ON DELETE SET NULL}) and writing an entry
 * should never require loading the actor aggregate.</p>
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Nullable: the FK is ON DELETE SET NULL, and system-initiated actions have no actor. */
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "action", nullable = false, updatable = false, length = 50)
    private String action;

    @Column(name = "entity_type", nullable = false, updatable = false, length = 50)
    private String entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> afterState;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
