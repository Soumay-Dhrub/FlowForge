package com.flowforge.engine.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A submission against a workflow (Requirement 9.1).
 *
 * <p>{@code requestData} is an open map because it is whatever the workflow's designer asked for —
 * an expense amount, a leave date range, a purchase justification. The platform does not know the
 * shape and deliberately does not constrain it; condition expressions on the graph's edges are
 * evaluated against exactly these keys (Requirement 9.4).
 *
 * @param requestData the submitted payload; may be {@code null}, read as empty
 */
public record CreateInstanceRequest(Map<String, Object> requestData) {

    /**
     * @return the payload, never {@code null}
     */
    public Map<String, Object> requestDataOrEmpty() {
        return requestData == null ? new LinkedHashMap<>() : new LinkedHashMap<>(requestData);
    }
}
