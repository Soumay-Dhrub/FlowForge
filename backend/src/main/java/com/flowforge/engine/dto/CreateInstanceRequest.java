package com.flowforge.engine.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public record CreateInstanceRequest(Map<String, Object> requestData) {

    /**
     * @return the payload, never {@code null}
     */
    public Map<String, Object> requestDataOrEmpty() {
        return requestData == null ? new LinkedHashMap<>() : new LinkedHashMap<>(requestData);
    }
}
