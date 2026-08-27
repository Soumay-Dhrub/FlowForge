package com.flowforge.report;

import com.flowforge.engine.WorkflowInstance;
import com.flowforge.task.Approval;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MetricsQueryRepository
        extends org.springframework.data.repository.Repository<WorkflowInstance, UUID> {

    @Query("""
            select distinct instance from WorkflowInstance instance
            join fetch instance.workflowVersion version
            join fetch version.workflow workflow
            left join fetch instance.initiatedBy initiator
            left join fetch initiator.department department
            where workflow.id = :workflowId
            """)
    List<WorkflowInstance> findInstancesOfWorkflow(@Param("workflowId") UUID workflowId);

    @Query("""
            select distinct approval from Approval approval
            join fetch approval.task task
            join fetch task.node node
            join fetch task.instance instance
            join instance.workflowVersion version
            join version.workflow workflow
            where workflow.id = :workflowId
            """)
    List<Approval> findApprovalsOfWorkflow(@Param("workflowId") UUID workflowId);
}
