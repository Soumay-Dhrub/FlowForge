package com.flowforge.report;

import com.flowforge.engine.WorkflowInstance;
import com.flowforge.task.Approval;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * The two reads a performance report needs (Requirements 21.1, 21.2, 21.3).
 *
 * <p>Extends Spring Data's bare {@code Repository} marker rather than {@code JpaRepository}: a
 * reporting seam should not be able to save or delete an instance, and inheriting thirty write and
 * paging methods to use two queries would hand it that ability for nothing.
 *
 * <p>Both queries live here rather than on {@code WorkflowInstanceRepository} or
 * {@code ApprovalRepository} so that reporting owns its own access paths. The engine's finders exist to
 * serve execution — one instance, locked, or one user's list — and an aggregate over a workflow's whole
 * population is a different shape with different fetch needs.
 *
 * <p>Both queries fetch the associations the metrics walk. Without the fetch joins, a workflow with
 * n instances would issue n lazy loads for the initiator and n more for the version, which is the
 * classic way a dashboard query becomes the slowest thing in the system.
 */
@Repository
public interface MetricsQueryRepository
        extends org.springframework.data.repository.Repository<WorkflowInstance, UUID> {

    /**
     * Every instance of every version of a workflow, with the initiator and their department attached.
     *
     * <p>Scoped to the workflow rather than to a version: a published workflow accumulates versions, and
     * "how does this process perform" is a question about the process, not about v3 of it. The filters
     * are applied afterwards, in the service, against exactly the predicate the DTO documents.
     *
     * @param workflowId the workflow to measure
     * @return its instances, in no particular order
     */
    @Query("""
            select distinct instance from WorkflowInstance instance
            join fetch instance.workflowVersion version
            join fetch version.workflow workflow
            left join fetch instance.initiatedBy initiator
            left join fetch initiator.department department
            where workflow.id = :workflowId
            """)
    List<WorkflowInstance> findInstancesOfWorkflow(@Param("workflowId") UUID workflowId);

    /**
     * Every recorded decision on a workflow, with its task and the node that raised it.
     *
     * <p>Returns approvals rather than tasks because an approval is what makes a dwell time measurable:
     * a task with no approval has a start but no end. The instance is fetched too, so the service can
     * drop decisions belonging to instances the filters excluded without a lazy load each.
     *
     * @param workflowId the workflow to measure
     * @return its approvals, in no particular order
     */
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
