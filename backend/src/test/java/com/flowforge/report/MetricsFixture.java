package com.flowforge.report;

import com.flowforge.engine.InstanceStatus;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.task.Approval;
import com.flowforge.task.Decision;
import com.flowforge.task.Task;
import com.flowforge.task.TaskStatus;
import com.flowforge.user.Department;
import com.flowforge.user.User;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.Workflow;
import com.flowforge.workflow.WorkflowNode;
import com.flowforge.workflow.WorkflowVersion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the object graph a performance report reads: a workflow with one published version, instances
 * with initiators in departments, and decided tasks at nodes.
 *
 * <p>Shared by the unit tests and the property test so both measure the same shape of data, and so the
 * property test's generators only have to produce numbers rather than entity graphs.
 */
final class MetricsFixture {

    private final Workflow workflow;
    private final WorkflowVersion version;
    private final List<WorkflowInstance> instances = new ArrayList<>();
    private final List<Approval> approvals = new ArrayList<>();

    MetricsFixture(String workflowName) {
        this.workflow = Workflow.builder()
                .id(UUID.randomUUID())
                .name(workflowName)
                .build();
        this.version = WorkflowVersion.builder()
                .id(UUID.randomUUID())
                .workflow(workflow)
                .versionNumber(1)
                .build();
    }

    Workflow workflow() {
        return workflow;
    }

    UUID workflowId() {
        return workflow.getId();
    }

    List<WorkflowInstance> instances() {
        return List.copyOf(instances);
    }

    List<Approval> approvals() {
        return List.copyOf(approvals);
    }

    /** A repository stub that answers exactly what has been seeded here. */
    MetricsQueryRepository repository() {
        return new MetricsQueryRepository() {
            @Override
            public List<WorkflowInstance> findInstancesOfWorkflow(UUID workflowId) {
                return workflowId.equals(workflow.getId()) ? instances() : List.of();
            }

            @Override
            public List<Approval> findApprovalsOfWorkflow(UUID workflowId) {
                return workflowId.equals(workflow.getId()) ? approvals() : List.of();
            }
        };
    }

    static Department department(String name) {
        return Department.builder().id(UUID.randomUUID()).name(name).build();
    }

    static User user(String name, Department department) {
        return User.builder()
                .id(UUID.randomUUID())
                .name(name)
                .email(name.toLowerCase().replace(' ', '.') + "@flowforge.local")
                .passwordHash("hash")
                .department(department)
                .isActive(true)
                .build();
    }

    WorkflowNode node(NodeType type, String label) {
        return WorkflowNode.builder()
                .id(UUID.randomUUID())
                .version(version)
                .type(type)
                .configJson(Map.of("label", label))
                .build();
    }

    /**
     * Seed one instance.
     *
     * @param initiator   who submitted it
     * @param status      where it ended up
     * @param startedAt   submission time
     * @param completedAt terminal time, or {@code null} for a running instance
     */
    WorkflowInstance instance(User initiator, InstanceStatus status, Instant startedAt, Instant completedAt) {
        WorkflowInstance instance = WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowVersion(version)
                .initiatedBy(initiator)
                .status(status)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .build();
        instances.add(instance);
        return instance;
    }

    /**
     * Seed a decided task at a node.
     *
     * @param instance  the instance the task belongs to
     * @param node      the node that raised it
     * @param createdAt when the node handed the work over
     * @param decidedAt when the decision was recorded
     * @param updatedAt the task row's last write, deliberately settable apart from {@code decidedAt} so a
     *                  test can prove which of the two the dwell time uses
     * @param decision  approve or reject
     */
    Approval decidedTask(
            WorkflowInstance instance,
            WorkflowNode node,
            Instant createdAt,
            Instant decidedAt,
            Instant updatedAt,
            Decision decision
    ) {
        User approver = instance.getInitiatedBy();
        Task task = Task.builder()
                .id(UUID.randomUUID())
                .instance(instance)
                .node(node)
                .assignedTo(approver)
                .status(TaskStatus.COMPLETED)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
        Approval approval = Approval.builder()
                .id(UUID.randomUUID())
                .task(task)
                .approver(approver)
                .decision(decision)
                .decidedAt(decidedAt)
                .build();
        approvals.add(approval);
        return approval;
    }
}
