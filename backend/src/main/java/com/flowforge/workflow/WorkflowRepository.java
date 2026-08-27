package com.flowforge.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {

    /**
     * List workflows in a given lifecycle status, newest first.
     */
    List<Workflow> findAllByStatusOrderByCreatedAtDesc(WorkflowStatus status);

    /**
     * List all workflows, newest first.
     */
    List<Workflow> findAllByOrderByCreatedAtDesc();

    /**
     * Case-insensitive name search used by the workflow list page.
     */
    List<Workflow> findByNameContainingIgnoreCaseOrderByCreatedAtDesc(String name);
}
