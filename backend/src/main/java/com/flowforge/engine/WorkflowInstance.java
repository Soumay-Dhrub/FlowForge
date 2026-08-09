package com.flowforge.engine;

import com.flowforge.user.User;
import com.flowforge.workflow.WorkflowNode;
import com.flowforge.workflow.WorkflowVersion;
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
 * One in-flight execution of a workflow definition.
 *
 * <p>The instance is bound to a {@link WorkflowVersion} — never to a {@link
 * com.flowforge.workflow.Workflow} — and the binding is made once, at submission time, against the
 * version that was current then (Requirement 9.1). {@code workflow_version_id} is therefore mapped
 * as non-updatable: publishing a new version later moves the {@code is_current} flag, and a running
 * instance must keep executing the definition it started on (Requirement 7.7).
 *
 * <p>{@link #currentNode} plus {@link #status} are the instance's durable position. The engine
 * persists both before advancing, which is what makes an instance resumable after a crash
 * (Requirement 9.3).
 *
 * <p>{@link #branchStatus} is the per-branch completion map an AND-Join reads; it stays empty until
 * task 19 populates it (Requirements 10.1–10.3).
 */
@Entity
@Table(name = "workflow_instances")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The published definition this instance executes, frozen at submission time. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_version_id", nullable = false, updatable = false)
    private WorkflowVersion workflowVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiated_by", nullable = false, updatable = false)
    private User initiatedBy;

    /** Where execution stands. Null only once the instance has left the graph entirely. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_node_id")
    private WorkflowNode currentNode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private InstanceStatus status = InstanceStatus.RUNNING;

    /** The submitted payload. Condition expressions are evaluated against this (Requirement 9.4). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_data", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> requestData = new LinkedHashMap<>();

    /** Per-branch completion bookkeeping for AND-Join synchronisation (Requirement 10.3). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "branch_status", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> branchStatus = new LinkedHashMap<>();

    @CreationTimestamp
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * @return {@code true} while the instance can still execute or is waiting on a decision
     */
    @Transient
    public boolean isRunning() {
        return status == InstanceStatus.RUNNING;
    }

    /**
     * The id of the version this instance is bound to, or {@code null} if unbound.
     *
     * <p>Deliberately not named {@code getWorkflowVersionId}: a JavaBean getter for an id that is
     * not a mapped attribute makes Spring Data resolve {@code ...WorkflowVersionId} in a derived
     * query name to this method and then fail, since Hibernate has no such attribute. Repository
     * finders traverse the association explicitly instead — see {@link WorkflowInstanceRepository}.
     *
     * @return the bound version id, or {@code null}
     */
    @Transient
    public UUID workflowVersionId() {
        return workflowVersion == null ? null : workflowVersion.getId();
    }

    /**
     * @return the id of the node execution stands at, or {@code null}
     */
    @Transient
    public UUID currentNodeId() {
        return currentNode == null ? null : currentNode.getId();
    }
}
