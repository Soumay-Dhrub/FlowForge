package com.flowforge.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkflowNodeRepository extends JpaRepository<WorkflowNode, UUID> {

    /**
     * All nodes of a version in a deterministic order.
     */
    List<WorkflowNode> findByVersionIdOrderByCreatedAtAscIdAsc(UUID versionId);

    /**
     * Nodes of a version filtered by type — used by graph validation to count Start and End nodes
     * (Requirements 7.1, 7.4).
     */
    List<WorkflowNode> findByVersionIdAndType(UUID versionId, NodeType type);

    /**
     * Look up a node scoped to its version.
     */
    Optional<WorkflowNode> findByIdAndVersionId(UUID id, UUID versionId);
}
