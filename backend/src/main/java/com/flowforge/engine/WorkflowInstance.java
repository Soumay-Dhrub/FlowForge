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
