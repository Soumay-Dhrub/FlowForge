package com.flowforge.engine;

import com.flowforge.common.response.ApiResponse;
import com.flowforge.engine.dto.CreateInstanceRequest;
import com.flowforge.engine.dto.WorkflowInstanceResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class InstanceController {

    private final WorkflowEngineService engine;
    private final WorkflowInstanceService instanceService;

    @PostMapping("/api/workflows/{workflowId}/instances")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<WorkflowInstanceResponse>> submit(
            @PathVariable UUID workflowId,
            @Valid @RequestBody(required = false) CreateInstanceRequest request,
            @AuthenticationPrincipal UUID callerId
    ) {
        CreateInstanceRequest effective = request == null ? new CreateInstanceRequest(null) : request;
        WorkflowInstance created =
                engine.createInstance(workflowId, callerId, effective.requestDataOrEmpty());

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                "Request submitted", instanceService.getInstance(created.getId())));
    }

    /**
     * One request in full, request payload included. The initiator or a privileged role.
     */
    @GetMapping("/api/instances/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER') "
            + "or @workflowInstanceService.isInitiator(#id, authentication.principal)")
    public ResponseEntity<ApiResponse<WorkflowInstanceResponse>> getInstance(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(instanceService.getInstance(id)));
    }

    /**
     * The caller's own submitted requests, newest first, without payloads (Requirement 20.2).
     */
    @GetMapping("/api/instances")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<WorkflowInstanceResponse>>> listMyInstances(
            @AuthenticationPrincipal UUID callerId
    ) {
        return ResponseEntity.ok(ApiResponse.success(instanceService.listMyInstances(callerId)));
    }

    /**
     * Stop a request and close the tasks anyone was still holding for it.
     *
     * <p>Returns 409 when the instance has already finished.
     */
    @PostMapping("/api/instances/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER') "
            + "or @workflowInstanceService.isInitiator(#id, authentication.principal)")
    public ResponseEntity<ApiResponse<WorkflowInstanceResponse>> cancelInstance(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID callerId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                "Request cancelled", instanceService.cancelInstance(id, callerId)));
    }
}
