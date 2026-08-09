package com.flowforge.workflow;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A single node on a {@link WorkflowVersion}'s graph.
 *
 * <p>{@code configJson} carries the type-specific configuration the execution engine reads at
 * runtime (label, assignee or approver role, timeout and escalation target, notification
 * recipients, and so on). {@code positionX}/{@code positionY} are canvas coordinates owned by the
 * builder UI (Requirement 6.1).
 */
@Entity
@Table(name = "workflow_nodes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private WorkflowVersion version;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private NodeType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> configJson = new LinkedHashMap<>();

    @Column(name = "position_x", nullable = false)
    @Builder.Default
    private Integer positionX = 0;

    @Column(name = "position_y", nullable = false)
    @Builder.Default
    private Integer positionY = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
