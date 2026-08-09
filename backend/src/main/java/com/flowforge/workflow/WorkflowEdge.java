package com.flowforge.workflow;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A directed transition between two {@link WorkflowNode}s of the same {@link WorkflowVersion}.
 *
 * <p>{@code conditionExpr} is optional; on the outgoing edges of a Condition node it holds a
 * boolean expression evaluated against the instance's request data (Requirements 6.2, 6.3).
 * Edges are read back in a deterministic order — see
 * {@link WorkflowEdgeRepository#findByVersionIdOrderByCreatedAtAscIdAsc(UUID)} — and the authored
 * order is additionally preserved in {@link WorkflowVersion#getGraphJson()}.
 */
@Entity
@Table(name = "workflow_edges")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private WorkflowVersion version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_node_id", nullable = false)
    private WorkflowNode sourceNode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_node_id", nullable = false)
    private WorkflowNode targetNode;

    @Column(name = "condition_expr", columnDefinition = "text")
    private String conditionExpr;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
