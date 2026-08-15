package com.flowforge.task;

import com.flowforge.common.response.ApiResponse;
import com.flowforge.task.dto.TaskDecisionRequest;
import com.flowforge.task.dto.TaskFilter;
import com.flowforge.task.dto.TaskResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task endpoints — a reviewer's queue and the decisions they record
 * (Requirements 12.1–12.3, 13.1–13.4).
 *
 * <h2>Authorization</h2>
 * <p>Both endpoints are open to any authenticated user, because a task list is self-service: every
 * employee has one. The scoping, not the role, is what protects the data — {@code GET /api/tasks}
 * answers with the caller's own tasks and an EMPLOYEE cannot ask for anyone else's, while ADMIN and
 * MANAGER may pass {@code assignedTo} to look across queues (Requirement 3.1).
 *
 * <p>Deciding a task is checked on ownership rather than on role: {@link TaskService#recordDecision}
 * refuses a task that is not the caller's with 403. A privileged role does not override that — an
 * approval has to be attributable to the person who actually made it, and letting an administrator
 * record someone else's decision would put a false name on the audit trail (Requirement 19.1).
 */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    /**
     * The caller's tasks, newest first, optionally narrowed (Requirements 12.1, 12.2, 12.3).
     *
     * @param status      only tasks in this status
     * @param workflowId  only tasks of instances of this workflow
     * @param createdFrom only tasks raised at or after this instant
     * @param createdTo   only tasks raised at or before this instant
     * @param assignedTo  whose queue to read; privileged roles only, and {@code all} for every task
     */
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

    /**
     * Record a decision and resume the instance (Requirements 13.1, 13.2, 13.3).
     *
     * <p>Rejecting without a comment returns 400; deciding someone else's task returns 403; deciding an
     * already-decided task returns 409.
     */
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

    /**
     * Whose tasks to list.
     *
     * <p>Defaults to the caller. A privileged role may name another user, or {@code all}; an
     * unprivileged caller asking for either is silently scoped back to themselves rather than refused,
     * because the honest answer to "show me everyone's tasks" for an employee is their own list, and a
     * 403 would confirm that other queues exist to probe.
     */
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
