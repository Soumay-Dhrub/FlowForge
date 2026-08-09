package com.flowforge.workflow;

import com.flowforge.common.response.ApiResponse;
import com.flowforge.workflow.dto.CloneWorkflowRequest;
import com.flowforge.workflow.dto.CreateWorkflowRequest;
import com.flowforge.workflow.dto.SaveDraftRequest;
import com.flowforge.workflow.dto.WorkflowResponse;
import com.flowforge.workflow.dto.WorkflowVersionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Workflow authoring endpoints.
 *
 * <p>Authorization follows the RBAC table in the design document (Requirements 3.1, 3.2). Workflow
 * authoring is a privileged activity, so every endpoint here is ADMIN or MANAGER:</p>
 * <ul>
 *   <li>{@code GET/POST /api/workflows} — ADMIN, MANAGER</li>
 *   <li>{@code GET /api/workflows/{id}} — ADMIN, MANAGER</li>
 *   <li>{@code PUT /api/workflows/{id}/versions/{vId}} — ADMIN, MANAGER</li>
 *   <li>{@code POST /api/workflows/{id}/clone} — ADMIN, MANAGER</li>
 * </ul>
 *
 * <p>Publishing is deliberately absent: the design reserves it for ADMIN and it arrives with
 * {@code WorkflowVersionService} in task 14. Employees never read definitions through this
 * controller — they interact with workflows through instances and tasks.</p>
 *
 * <p>Requests with no, expired, or malformed token never reach these methods — the security filter
 * chain rejects them with 401 (Requirement 3.3). {@code JwtAuthenticationFilter} sets the principal
 * to the caller's UUID, which is what {@code @AuthenticationPrincipal} resolves below.</p>
 */
@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    /**
     * List workflows, newest first, optionally filtered by a name fragment.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<WorkflowResponse>>> listWorkflows(
            @RequestParam(name = "name", required = false) String name
    ) {
        return ResponseEntity.ok(ApiResponse.success(workflowService.listWorkflows(name)));
    }

    /**
     * Create a workflow. The response carries the blank draft version to author against.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<WorkflowResponse>> createWorkflow(
            @Valid @RequestBody CreateWorkflowRequest request,
            @AuthenticationPrincipal UUID actorId
    ) {
        WorkflowResponse created = workflowService.createWorkflow(request, actorId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Workflow created", created));
    }

    /**
     * Fetch one workflow with its full version history (Requirement 8.3).
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<WorkflowResponse>> getWorkflow(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(workflowService.getWorkflow(id)));
    }

    /**
     * Save the canvas state into a draft version (Requirements 6.4, 6.5). Returns 409 when the
     * target version is published, and 422 when the graph payload is incoherent.
     */
    @PutMapping("/{id}/versions/{versionId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<WorkflowVersionResponse>> saveDraft(
            @PathVariable UUID id,
            @PathVariable UUID versionId,
            @Valid @RequestBody SaveDraftRequest request
    ) {
        WorkflowVersionResponse saved = workflowService.saveDraft(id, versionId, request);
        return ResponseEntity.ok(ApiResponse.success("Draft saved", saved));
    }

    /**
     * Clone a workflow into a new draft definition (Requirements 8.1, 8.2). The body is optional.
     */
    @PostMapping("/{id}/clone")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<WorkflowResponse>> cloneWorkflow(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) CloneWorkflowRequest request,
            @AuthenticationPrincipal UUID actorId
    ) {
        WorkflowResponse clone = workflowService.cloneWorkflow(id, request, actorId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Workflow cloned", clone));
    }
}
