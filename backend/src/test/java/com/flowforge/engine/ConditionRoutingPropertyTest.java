package com.flowforge.engine;

import com.flowforge.audit.AuditLogService;
import com.flowforge.engine.executors.TaskNodeExecutor;
import com.flowforge.task.Task;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.Workflow;
import com.flowforge.workflow.WorkflowNode;
import com.flowforge.workflow.WorkflowVersion;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 10: Condition Evaluation Routes to Correct Edge.
 *
 * <p>For any Condition node with any set of outgoing edges, and any request payload, the engine takes
 * the <em>first</em> edge in authored order whose condition holds for that payload, and marks the
 * instance ERROR when none of them does (Requirements 9.4, 9.5).
 *
 * <h2>The oracle</h2>
 * <p>Which edge should win is decided by a hand-written Java predicate attached to each generated
 * condition, evaluated over the generated map in the test itself. Nothing about SpEL, edge ordering or
 * the executor is reused to compute the expectation, so the test can disagree with the implementation —
 * which is the only way it can catch it being wrong. Each generated condition therefore carries two
 * independent statements of the same intent: the expression the workflow is authored with, and the
 * predicate the test believes it means.
 *
 * <p>Branch targets are Task nodes, which pause. So the branch taken is observable as the node the
 * instance is sitting on afterwards, and the single task raised confirms that branch actually executed
 * rather than merely being pointed at. Conditions are drawn with repetition and include overlapping
 * predicates and an unconditioned fallback edge, so the generated graphs cover several simultaneously
 * true conditions (where only ordering decides), a fallback shadowing everything after it, and edge
 * sets — including the empty one — where nothing matches at all.
 *
 * <p><b>Validates: Requirements 9.4, 9.5</b></p>
 */
@Tag("flowforge")
class ConditionRoutingPropertyTest {

    /**
     * A condition a generated edge can carry: the SpEL the designer authors, and — written separately —
     * what the test independently believes it means.
     *
     * <p>{@link #FALLBACK} carries no expression, which is the unconditional else branch.
     */
    private enum Condition {

        AMOUNT_OVER_100("amount > 100", data -> amount(data) > 100),
        AMOUNT_OVER_1000("amount > 1000", data -> amount(data) > 1000),
        AMOUNT_AT_MOST_50("amount <= 50", data -> amount(data) <= 50),
        FINANCE("department == 'FINANCE'", data -> "FINANCE".equals(data.get("department"))),
        NOT_LEGAL("department != 'LEGAL'", data -> !"LEGAL".equals(data.get("department"))),
        URGENT("urgent == true", data -> urgent(data)),
        NOT_URGENT("!(urgent == true)", data -> !urgent(data)),
        BIG_AND_URGENT("amount > 500 and urgent == true", data -> amount(data) > 500 && urgent(data)),
        SHORT_OR_HR("days <= 5 or department == 'HR'",
                data -> days(data) <= 5 || "HR".equals(data.get("department"))),
        NAMED_KEY("#request['total amount'] > 2000", data -> total(data) > 2000),
        FALLBACK(null, data -> true);

        private final String expression;
        private final Predicate<Map<String, Object>> oracle;

        Condition(String expression, Predicate<Map<String, Object>> oracle) {
            this.expression = expression;
            this.oracle = oracle;
        }

        private static int amount(Map<String, Object> data) {
            return (int) data.get("amount");
        }

        private static int days(Map<String, Object> data) {
            return (int) data.get("days");
        }

        private static int total(Map<String, Object> data) {
            return (int) data.get("total amount");
        }

        private static boolean urgent(Map<String, Object> data) {
            return (boolean) data.get("urgent");
        }
    }

    @Property(tries = 100)
    @Label("Property 10: a condition node follows the first matching edge, and errors when none matches")
    void conditionEvaluationRoutesToTheFirstMatchingEdge(
            @ForAll("conditionSets") List<Condition> conditions,
            @ForAll("requestPayloads") Map<String, Object> requestData
    ) {
        InMemoryEngineFixture fixture = new InMemoryEngineFixture();
        fixture.registerTask17Executors();
        fixture.registerTask18Executors();

        Workflow workflow = fixture.workflow("Generated Routing");
        WorkflowVersion version = fixture.version(workflow, 1, true, true);
        WorkflowNode start = fixture.node(version, NodeType.START);
        WorkflowNode condition = fixture.node(version, NodeType.CONDITION);
        fixture.edge(start, condition, null);

        List<WorkflowNode> branches = new ArrayList<>();
        for (Condition generated : conditions) {
            WorkflowNode target = fixture.node(version, NodeType.TASK);
            target.getConfigJson().put(TaskNodeExecutor.CONFIG_ASSIGNEE_ROLE, "MANAGER");
            fixture.edge(condition, target, generated.expression);
            branches.add(target);
        }

        // The oracle: the first authored condition the test itself believes holds for this payload.
        Optional<Integer> expectedWinner = firstMatch(conditions, requestData);

        WorkflowInstance instance = fixture.engine()
                .createInstance(workflow.getId(), fixture.initiator.getId(), requestData);

        if (expectedWinner.isPresent()) {
            UUID expectedNode = branches.get(expectedWinner.get()).getId();
            assertThat(instance.currentNodeId())
                    .as("first matching edge in authored order wins: %s over %s",
                            conditions.get(expectedWinner.get()), conditions)
                    .isEqualTo(expectedNode);
            assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
            assertThat(fixture.tasksOfInstance(instance.getId()))
                    .as("exactly the chosen branch executed")
                    .singleElement()
                    .extracting(Task::nodeId)
                    .isEqualTo(expectedNode);
            assertThat(fixture.auditEntriesWithAction(AuditLogService.ACTION_INSTANCE_ERROR)).isEmpty();
        } else {
            assertThat(instance.getStatus())
                    .as("nothing matched among %s, so the instance errors", conditions)
                    .isEqualTo(InstanceStatus.ERROR);
            assertThat(instance.currentNodeId())
                    .as("and is left on the node it could not be routed out of")
                    .isEqualTo(condition.getId());
            assertThat(fixture.tasksOfInstance(instance.getId())).isEmpty();
            assertThat(fixture.auditEntriesWithAction(AuditLogService.ACTION_INSTANCE_ERROR))
                    .as("with a descriptive audit entry (Requirement 9.5)")
                    .singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.getEntityId()).isEqualTo(instance.getId());
                        assertThat(String.valueOf(entry.getAfterState().get("reason")))
                                .contains(condition.getId().toString());
                    });
        }
    }

    private Optional<Integer> firstMatch(List<Condition> conditions, Map<String, Object> requestData) {
        for (int index = 0; index < conditions.size(); index++) {
            if (conditions.get(index).oracle.test(requestData)) {
                return Optional.of(index);
            }
        }
        return Optional.empty();
    }

    // ── generators ───────────────────────────────────────────────────────────────────────────────

    /**
     * Zero to five outgoing edges, drawn with repetition from the catalogue.
     *
     * <p>Zero is included on purpose: a Condition node with no way out is the same "nothing matched"
     * outcome by the same reading. Repetition and overlap are what make ordering matter.
     */
    @Provide
    Arbitrary<List<Condition>> conditionSets() {
        return Arbitraries.of(Condition.values()).list().ofMinSize(0).ofMaxSize(5);
    }

    /**
     * A payload carrying every field the catalogue can reference, so an expression is never evaluated
     * against a missing field — that is a definition defect with its own unit test, not routing.
     */
    @Provide
    Arbitrary<Map<String, Object>> requestPayloads() {
        Arbitrary<Integer> amounts = Arbitraries.integers().between(0, 5000);
        Arbitrary<Integer> totals = Arbitraries.integers().between(0, 5000);
        Arbitrary<Integer> days = Arbitraries.integers().between(0, 30);
        Arbitrary<String> departments = Arbitraries.of("FINANCE", "LEGAL", "HR", "IT");
        Arbitrary<Boolean> urgency = Arbitraries.of(true, false);

        return Combinators.combine(amounts, totals, days, departments, urgency)
                .as((amount, total, dayCount, department, urgent) -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("amount", amount);
                    payload.put("total amount", total);
                    payload.put("days", dayCount);
                    payload.put("department", department);
                    payload.put("urgent", urgent);
                    return payload;
                });
    }
}
