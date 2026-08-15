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

/**
 * Submitting requests against workflows, and reading or stopping them
 * (Requirements 9.1, 12.3, 20.2).
 *
 * <h2>Authorization</h2>
 * <p>Submitting is open to any authenticated user: raising a request is the one thing every employee
 * does, and which workflows exist is not a secret worth a role check.
 *
 * <p>Reading and cancelling are the initiator's, or a privileged role's. The expressions call
 * {@link WorkflowInstanceService#isInitiator} rather than comparing a field, because ownership lives
 * behind the instance's association and a {@code @PreAuthorize} string is the wrong place to walk an
 * object graph.
 *
 * <p>Cancelling is not restricted to the initiator alone: a manager needs to be able to stop a request
 * whose submitter has left, and an administrator needs it to clear a stuck instance. Both are recorded
 * against the actor in the audit trail (Requirement 19.1).
 */
@RestController
@RequiredArgsConstructor
public class InstanceController {

    private final WorkflowEngineService engine;
    private final WorkflowInstanceService instanceService;

    /**
     * Submit a request against a workflow's published definition (Requirement 9.1).
     *
     * <p>Returns 409 when the workflow has nothing published — the definition exists but does not yet
     * accept submissions.
     */
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
