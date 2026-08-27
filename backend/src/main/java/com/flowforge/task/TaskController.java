package com.flowforge.task;

import com.flowforge.common.response.ApiResponse;
import com.flowforge.task.dto.DelegateTasksRequest;
import com.flowforge.task.dto.DelegationResponse;
import com.flowforge.task.dto.TaskDecisionRequest;
import com.flowforge.task.dto.TaskFilter;
import com.flowforge.task.dto.TaskResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<TaskResponse>>> listTasks(
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) UUID workflowId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @RequestParam(required = false) String assignedTo,
            @AuthenticationPrincipal UUID callerId,
            Authentication authentication
    ) {
        UUID scope = resolveScope(assignedTo, callerId, authentication);
        TaskFilter filter = new TaskFilter(status, workflowId, createdFrom, createdTo);
        return ResponseEntity.ok(ApiResponse.success(taskService.listTasks(scope, filter)));
    }

    /**
     * One task in detail. Scoped by the service's ownership rules on decision; reading is open to any
     * authenticated user who knows the id, which is how a manager follows a link from a report.
     */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<TaskResponse>> getTask(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(taskService.getTask(id)));
    }

    @PatchMapping("/{id}/decision")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<TaskResponse>> recordDecision(
            @PathVariable UUID id,
            @Valid @RequestBody TaskDecisionRequest request,
            @AuthenticationPrincipal UUID callerId
    ) {
        TaskResponse decided = taskService.recordDecision(id, callerId, request);
        return ResponseEntity.ok(ApiResponse.success("Decision recorded", decided));
    }

    @PostMapping("/{id}/delegate")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<DelegationResponse>> delegate(
            @PathVariable UUID id,
            @Valid @RequestBody DelegateTasksRequest request,
            @AuthenticationPrincipal UUID callerId
    ) {
        DelegationResponse delegation = taskService.delegateFromTask(id, callerId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                "%d pending task(s) delegated".formatted(delegation.reassignedTaskCount()),
                delegation));
    }

    private UUID resolveScope(String assignedTo, UUID callerId, Authentication authentication) {
        if (assignedTo == null || assignedTo.isBlank() || !isPrivileged(authentication)) {
            return callerId;
        }
        if ("all".equalsIgnoreCase(assignedTo.trim())) {
            return null;
        }
        try {
            return UUID.fromString(assignedTo.trim());
        } catch (IllegalArgumentException notAnId) {
            return callerId;
        }
    }

    private boolean isPrivileged(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority) || "ROLE_MANAGER".equals(authority));
    }
}
