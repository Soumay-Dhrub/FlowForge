package com.flowforge.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for WorkflowEdge entity operations.
 */
@Repository
public interface WorkflowEdgeRepository extends JpaRepository<WorkflowEdge, UUID> {

    /**
     * All edges of a version in a deterministic order.
     */
    List<WorkflowEdge> findByVersionIdOrderByCreatedAtAscIdAsc(UUID versionId);

    /**
     * Outgoing edges of a node in a deterministic order — the engine evaluates a Condition node's
     * edges in this order and follows the first match (Requirements 9.4, 9.5).
     */
    List<WorkflowEdge> findBySourceNodeIdOrderByCreatedAtAscIdAsc(UUID sourceNodeId);

    /**
     * Inbound edges of a node — used by AND-Join branch bookkeeping (Requirement 10.2).
     */
    List<WorkflowEdge> findByTargetNodeIdOrderByCreatedAtAscIdAsc(UUID targetNodeId);
}
