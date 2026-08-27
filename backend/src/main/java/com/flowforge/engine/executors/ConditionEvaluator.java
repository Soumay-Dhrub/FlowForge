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
 * Evaluates an edge's SpEL condition against the instance's request data. An edge with no expression
 * always matches.
 *
 * <p>The expression is user-authored, so the evaluation context is deliberately locked down. Do not
 * swap in StandardEvaluationContext: T(java.lang.Runtime), bean references and constructors would all
 * become reachable.
 */
@Component
@Slf4j
public class ConditionEvaluator {

    private final ExpressionParser parser = new SpelExpressionParser();

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
