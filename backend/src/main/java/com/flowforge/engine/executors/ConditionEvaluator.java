package com.flowforge.engine.executors;

import com.flowforge.common.exception.AppException;
import com.flowforge.workflow.WorkflowEdge;
import com.flowforge.workflow.WorkflowNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Decides whether one edge's condition expression holds for one instance's request data
 * (Requirement 9.4).
 *
 * <p>Expressions are Spring Expression Language, authored on the canvas by an ADMIN or MANAGER and
 * evaluated here server-side, with the instance's {@code request_data} as the root object. So
 * {@code amount > 1000 and department == 'FINANCE'} reads the submitted payload's {@code amount} and
 * {@code department} fields directly; {@code #request['total amount']} does the same for keys that are
 * not valid identifiers.
 *
 * <h2>The sandbox</h2>
 * <p>A condition expression is workflow configuration, not code, and it must not be able to become
 * code. Evaluating it with SpEL's {@code StandardEvaluationContext} would make it exactly that: type
 * references make {@code T(java.lang.Runtime).getRuntime().exec(...)} live remote code execution, bean
 * references reach every service in the container, and constructors reach the filesystem. The
 * evaluation context here is built to take all of that away:
 * <ul>
 *   <li><b>{@link SimpleEvaluationContext}</b>, not the standard one — it has no bean resolver, and
 *       its type locator refuses every {@code T(...)} reference and every {@code new ...}
 *       construction.</li>
 *   <li><b>Only a {@link MapAccessor}</b> is registered, so the only readable properties are keys of
 *       the request data map. No reflective bean property access is available, on the root or on any
 *       value reached from it.</li>
 *   <li><b>No method resolvers</b> are registered, so no method can be invoked on anything — which is
 *       what closes the usual escape of reaching a {@code Class} object through an ordinary value.</li>
 *   <li><b>An unmodifiable view of the request data</b> is the root, so an expression cannot use
 *       assignment to rewrite the payload it is supposed to be inspecting.</li>
 * </ul>
 * <p>Each of those is a refusal at evaluation time, which lands in the failure handling below — a
 * malicious expression stops the instance with a message, never executes.
 *
 * <h2>Two deliberate readings</h2>
 * <p><b>An edge with no expression is an unconditional fallback: it always matches.</b> A condition
 * on an edge is optional (Requirement 6.2), and Requirement 9.4 takes the first edge whose condition
 * matches, so an edge carrying no condition places no restriction on the path and matches by
 * definition. That is how a designer draws "otherwise": because evaluation follows the authored order
 * and stops at the first match, an unconditioned edge placed last behaves exactly like an {@code else}
 * branch, and one placed first deliberately shadows the rest — visible on the canvas, since edge order
 * is what the designer sees. The alternative reading, that a blank condition never matches, would
 * leave no way to express a default branch at all and would make Requirement 9.5's ERROR the
 * inevitable outcome of forgetting to type an expression.
 *
 * <p><b>An expression that cannot produce a boolean stops the instance loudly; it is not read as
 * "false".</b> This covers a syntax error, a non-boolean result, a reference the sandbox refuses, and
 * a field the payload does not carry. Requirement 9.5 reserves ERROR for the case where every edge was
 * evaluated and none matched, which is a statement about the request, not about the definition — so a
 * broken expression is not that case and must not be disguised as it. Nor can it be treated as a
 * non-match: evaluation would simply carry on to the next edge and quite possibly find one that
 * matches, routing the request down a plausible-looking wrong path with nothing to show that a
 * condition was never really evaluated. It therefore throws an {@link AppException} naming the node,
 * the edge and the expression, which {@code GlobalExceptionHandler} renders as that message and
 * nothing else — no stack trace, no internals. The transaction rolls back, so the instance stays where
 * it was rather than being left half-routed (Requirement 9.3).
 */
@Component
@Slf4j
public class ConditionEvaluator {

    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * Whether an edge may be taken for this request data.
     *
     * @param node        the Condition node being executed, named in any failure message
     * @param edge        the outgoing edge whose condition is being tested
     * @param requestData the instance's submitted payload; {@code null} is treated as empty
     * @return {@code true} when the edge carries no condition, or when its condition evaluates to
     *         {@code true}
     * @throws AppException 500 when the expression cannot be evaluated to a boolean
     */
    public boolean matches(WorkflowNode node, WorkflowEdge edge, Map<String, Object> requestData) {
        String expression = edge.getConditionExpr() == null ? "" : edge.getConditionExpr().trim();
        if (expression.isEmpty()) {
            log.debug("Edge {} of condition node {} carries no condition; it is the fallback branch",
                    edge.getId(), node.getId());
            return true;
        }

        Object result;
        try {
            Expression parsed = parser.parseExpression(expression);
            result = parsed.getValue(contextFor(requestData), Object.class);
        } catch (RuntimeException failure) {
            // Deliberately reports the parser/evaluator message, not the throwable: the designer needs
            // to know what about their expression was rejected, the caller must not see internals.
            throw defect(node, edge, expression,
                    "could not be evaluated: " + rootCauseMessage(failure));
        }

        if (result instanceof Boolean matched) {
            log.debug("Edge {} of condition node {} evaluated '{}' to {}",
                    edge.getId(), node.getId(), expression, matched);
            return matched;
        }
        throw defect(node, edge, expression, result == null
                ? "evaluated to null; a condition must evaluate to true or false"
                : "evaluated to a %s, not a boolean".formatted(result.getClass().getSimpleName()));
    }

    /**
     * Whether an edge's condition is syntactically usable, without running it (Requirement 7.5).
     *
     * <p>Publish-time counterpart to {@link #matches}. Only parsing is checked, because that is all
     * that can be known without a payload: whether the expression <em>holds</em> depends on the request,
     * and an expression referring to a key no request happens to carry is not a defect — SpEL reads a
     * missing map key as null, which is a legitimate way to write "unset means no".
     *
     * <p>Returns the violation rather than throwing, because this exists to produce the sentence a
     * designer reads. The message is the same one {@link #matches} would raise at runtime, so the two
     * cannot describe the same fault differently.
     *
     * @param node the condition node the edge leaves
     * @param edge the edge whose expression to check
     * @return the violation, or empty when the edge carries no condition or one that parses
     */
    public Optional<String> validate(WorkflowNode node, WorkflowEdge edge) {
        String expression = edge.getConditionExpr() == null ? "" : edge.getConditionExpr().trim();
        if (expression.isEmpty()) {
            return Optional.empty();
        }

        try {
            parser.parseExpression(expression);
            return Optional.empty();
        } catch (RuntimeException failure) {
            return Optional.of(defect(node, edge, expression,
                    "could not be parsed: " + rootCauseMessage(failure)).getMessage());
        }
    }

    /**
     * A context that can read the request data and do nothing else.
     *
     * <p>Built per evaluation: it is cheap, and a fresh context means one edge's expression cannot
     * leave state behind for the next edge's.
     */
    private EvaluationContext contextFor(Map<String, Object> requestData) {
        Map<String, Object> readOnly =
                Collections.unmodifiableMap(requestData == null ? Map.of() : requestData);
        SimpleEvaluationContext context = SimpleEvaluationContext.forPropertyAccessors(new MapAccessor())
                .withRootObject(readOnly)
                .build();
        // Named access for keys that are not valid identifiers: #request['total amount'].
        context.setVariable("request", readOnly);
        return context;
    }

    private AppException defect(WorkflowNode node, WorkflowEdge edge, String expression, String problem) {
        return new AppException(
                "Condition node %s edge %s expression '%s' %s".formatted(
                        node == null ? null : node.getId(), edge.getId(), expression, problem),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * The innermost message, which is where SpEL puts the useful part (the unresolvable property, the
     * refused type reference, the position of the syntax error).
     */
    private String rootCauseMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
