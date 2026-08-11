package com.flowforge.engine;

import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.engine.executors.TaskNodeExecutor;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.Workflow;
import com.flowforge.workflow.WorkflowNode;
import com.flowforge.workflow.WorkflowVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@code ConditionNodeExecutor}: routing on request data, and what happens when it
 * cannot be done (Requirements 9.4, 9.5).
 *
 * <p>The graph is Start → Condition → one Task node per branch. A Task node pauses, so where the
 * instance is sitting afterwards is the branch it took, and an instance that could not be routed is
 * unmistakably different: ERROR, still on the Condition node.
 */
class ConditionNodeExecutorTest {

    /**
     * Proof of whether a sandboxed expression ran or was refused.
     *
     * <p>Asserting that a malicious expression throws is not on its own proof that it did not execute —
     * it could have run and then failed. A static method the expression tries to call, that records
     * being called, distinguishes the two.
     */
    public static final class Probe {

        private static boolean tripped;

        private Probe() {
        }

        /** Called only if SpEL is able to reach a static method. */
        public static boolean trip() {
            tripped = true;
            return true;
        }

        static void reset() {
            tripped = false;
        }

        static boolean wasTripped() {
            return tripped;
        }
    }

    private InMemoryEngineFixture fixture;
    private Workflow workflow;
    private WorkflowVersion version;
    private WorkflowNode start;
    private WorkflowNode condition;
    private boolean registered;

    @BeforeEach
    void setUp() {
        Probe.reset();
        registered = false;
        fixture = new InMemoryEngineFixture();
        workflow = fixture.workflow("Expense Routing");
        version = fixture.version(workflow, 1, true, true);
        start = fixture.node(version, NodeType.START);
        condition = fixture.node(version, NodeType.CONDITION);
        fixture.edge(start, condition, null);
    }

    // ── first match wins (Requirement 9.4) ───────────────────────────────────────────────────────

    @Test
    void execute_withSeveralMatchingEdges_takesTheFirstInAuthoredOrder() {
        WorkflowNode first = branch("amount > 100");
        WorkflowNode second = branch("amount > 500");
        WorkflowNode third = branch("amount > 1000");

        WorkflowInstance instance = submit(Map.of("amount", 5000));

        assertThat(instance.currentNodeId())
                .as("all three conditions hold; authored order decides, like an if/else-if chain")
                .isEqualTo(first.getId());
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(fixture.tasksOfInstance(instance.getId()))
                .as("only the branch that was taken did any work")
                .singleElement()
                .satisfies(task -> assertThat(task.nodeId()).isEqualTo(first.getId()));
        assertThat(second.getId()).isNotEqualTo(instance.currentNodeId());
        assertThat(third.getId()).isNotEqualTo(instance.currentNodeId());
    }

    @Test
    void execute_skipsEdgesWhoseConditionIsFalse() {
        branch("amount > 10000");
        branch("department == 'LEGAL'");
        WorkflowNode matching = branch("amount > 500 and department == 'FINANCE'");

        WorkflowInstance instance = submit(Map.of("amount", 900, "department", "FINANCE"));

        assertThat(instance.currentNodeId()).isEqualTo(matching.getId());
    }

    /** Keys that are not valid identifiers are still reachable, through the {@code #request} variable. */
    @Test
    void execute_readsRequestFieldsThatAreNotValidIdentifiers() {
        WorkflowNode matching = branch("#request['total amount'] >= 250");
        branch(null);

        WorkflowInstance instance = submit(Map.of("total amount", 250));

        assertThat(instance.currentNodeId()).isEqualTo(matching.getId());
    }

    // ── the unconditioned edge is the fallback ───────────────────────────────────────────────────

    @Test
    void execute_treatsAnEdgeWithoutAConditionAsAnUnconditionalFallback() {
        branch("amount > 10000");
        WorkflowNode fallback = branch(null);

        WorkflowInstance instance = submit(Map.of("amount", 10));

        assertThat(instance.currentNodeId())
                .as("an edge carrying no condition places no restriction, so it is the else branch")
                .isEqualTo(fallback.getId());
        assertThat(fixture.auditEntriesWithAction(AuditLogService.ACTION_INSTANCE_ERROR)).isEmpty();
    }

    @Test
    void execute_treatsABlankConditionTheSameAsNoCondition() {
        branch("amount > 10000");
        WorkflowNode fallback = branch("   ");

        assertThat(submit(Map.of("amount", 10)).currentNodeId()).isEqualTo(fallback.getId());
    }

    // ── nothing matched (Requirement 9.5) ────────────────────────────────────────────────────────

    @Test
    void execute_withNoMatchingEdge_marksTheInstanceErrorAndAudits() {
        branch("amount > 10000");
        branch("department == 'LEGAL'");

        WorkflowInstance instance = submit(Map.of("amount", 10, "department", "FINANCE"));

        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.ERROR);
        assertThat(instance.currentNodeId())
                .as("the instance is left on the node it could not be routed out of")
                .isEqualTo(condition.getId());
        assertThat(instance.getCompletedAt()).isNotNull();
        assertThat(fixture.tasksById).as("no branch ran").isEmpty();
        assertThat(fixture.auditEntriesWithAction(AuditLogService.ACTION_INSTANCE_ERROR))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.getEntityType()).isEqualTo(AuditLogService.ENTITY_WORKFLOW_INSTANCE);
                    assertThat(entry.getEntityId()).isEqualTo(instance.getId());
                    assertThat(entry.getAfterState()).containsEntry("status", "ERROR");
                    assertThat(String.valueOf(entry.getAfterState().get("reason")))
                            .as("the entry describes why routing failed and what was tried")
                            .contains(condition.getId().toString())
                            .contains("No outgoing edge condition matched")
                            .contains("amount > 10000")
                            .contains("department == 'LEGAL'");
                });
    }

    /** Every condition was evaluated and none matched — there were none. Same outcome, same reading. */
    @Test
    void execute_withNoOutgoingEdges_marksTheInstanceError() {
        WorkflowInstance instance = submit(Map.of("amount", 10));

        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.ERROR);
        assertThat(fixture.auditEntriesWithAction(AuditLogService.ACTION_INSTANCE_ERROR))
                .singleElement()
                .satisfies(entry -> assertThat(String.valueOf(entry.getAfterState().get("reason")))
                        .contains("no outgoing edges"));
    }

    // ── a broken expression is a definition defect, not a non-match ──────────────────────────────

    @Test
    void execute_withAMalformedExpression_failsLoudlyWithoutRouting() {
        branch("amount >");
        WorkflowNode wouldMatch = branch(null);

        assertThatThrownBy(this::submitEmpty)
                .isInstanceOf(AppException.class)
                .hasMessageContaining(condition.getId().toString())
                .hasMessageContaining("amount >")
                .hasMessageContaining("could not be evaluated")
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(fixture.tasksById)
                .as("a broken condition must not be read as false and fall through to the next edge")
                .isEmpty();
        assertThat(fixture.instancesById.values())
                .allSatisfy(stored -> assertThat(stored.currentNodeId()).isNotEqualTo(wouldMatch.getId()));
        assertThat(fixture.auditEntriesWithAction(AuditLogService.ACTION_INSTANCE_ERROR))
                .as("ERROR is reserved for 'every condition was evaluated and none matched'")
                .isEmpty();
    }

    @Test
    void execute_withANonBooleanExpression_failsLoudly() {
        branch("amount + 1");

        assertThatThrownBy(() -> submit(Map.of("amount", 3)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("not a boolean");
    }

    @Test
    void execute_withAConditionOnAFieldTheRequestDoesNotCarry_failsLoudly() {
        branch("amount > 100");

        assertThatThrownBy(() -> submit(Map.of("department", "FINANCE")))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("could not be evaluated")
                .hasMessageContaining("amount");
    }

    // ── the sandbox ──────────────────────────────────────────────────────────────────────────────

    /**
     * A condition expression is configuration, not code. Each of these is a real SpEL escape under a
     * {@code StandardEvaluationContext}; all of them must be refused, and the probe proves refused
     * rather than executed-then-failed.
     */
    @Test
    void execute_refusesExpressionsThatReachTypesBeansOrConstructors() {
        String[] malicious = {
                "T(java.lang.Runtime).getRuntime().exec('touch /tmp/flowforge-pwned')",
                "T(com.flowforge.engine.ConditionNodeExecutorTest.Probe).trip()",
                "T(java.lang.System).getProperty('user.home') != null",
                "new java.io.File('/etc/passwd').exists()",
                "@workflowRepository != null",
                "amount.getClass().forName('java.lang.Runtime') != null",
                "''.class.name == 'x'",
        };

        for (String expression : malicious) {
            InMemoryEngineFixture attempt = freshFixtureWithCondition(expression);
            WorkflowEngineService engine = attempt.engine();
            UUID workflowId = attempt.workflowsById.keySet().iterator().next();
            UUID userId = attempt.initiator.getId();

            assertThatThrownBy(() -> engine.createInstance(workflowId, userId, Map.of("amount", 1)))
                    .as("expression '%s' must be refused", expression)
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("could not be evaluated");
            assertThat(attempt.tasksById).as("no branch was taken for '%s'", expression).isEmpty();
        }

        assertThat(Probe.wasTripped())
                .as("the sandbox refused the expression rather than running it")
                .isFalse();
    }

    /** The payload is inspected, never rewritten: assignment cannot reach through the read-only view. */
    @Test
    void execute_refusesAnExpressionThatTriesToRewriteTheRequestData() {
        branch("(amount = 1) == 1");
        Map<String, Object> submitted = new LinkedHashMap<>(Map.of("amount", 900));

        assertThatThrownBy(() -> submit(submitted))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("could not be evaluated");
        assertThat(fixture.instancesById.values())
                .allSatisfy(stored -> assertThat(stored.getRequestData()).containsEntry("amount", 900));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** A Task-node branch off the Condition node, reached by an edge carrying {@code conditionExpr}. */
    private WorkflowNode branch(String conditionExpr) {
        WorkflowNode target = fixture.node(version, NodeType.TASK);
        target.getConfigJson().put(TaskNodeExecutor.CONFIG_ASSIGNEE_ROLE, "MANAGER");
        fixture.edge(condition, target, conditionExpr);
        return target;
    }

    private WorkflowInstance submit(Map<String, Object> requestData) {
        registerExecutors();
        return fixture.engine().createInstance(workflow.getId(), fixture.initiator.getId(), requestData);
    }

    private WorkflowInstance submitEmpty() {
        return submit(Map.of());
    }

    private void registerExecutors() {
        if (registered) {
            return;
        }
        fixture.registerTask17Executors();
        fixture.registerTask18Executors();
        registered = true;
    }

    /** A separate graph per malicious expression, so one refusal cannot mask the next. */
    private InMemoryEngineFixture freshFixtureWithCondition(String expression) {
        InMemoryEngineFixture attempt = new InMemoryEngineFixture();
        Workflow attemptWorkflow = attempt.workflow("Sandbox");
        WorkflowVersion attemptVersion = attempt.version(attemptWorkflow, 1, true, true);
        WorkflowNode attemptStart = attempt.node(attemptVersion, NodeType.START);
        WorkflowNode attemptCondition = attempt.node(attemptVersion, NodeType.CONDITION);
        WorkflowNode attemptTarget = attempt.node(attemptVersion, NodeType.TASK);
        attemptTarget.getConfigJson().put(TaskNodeExecutor.CONFIG_ASSIGNEE_ROLE, "MANAGER");
        attempt.edge(attemptStart, attemptCondition, null);
        attempt.edge(attemptCondition, attemptTarget, expression);
        attempt.registerTask17Executors();
        attempt.registerTask18Executors();
        return attempt;
    }
}
