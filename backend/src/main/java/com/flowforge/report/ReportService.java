package com.flowforge.report;

import com.flowforge.audit.AuditLog;
import com.flowforge.audit.AuditLogRepository;
import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.engine.WorkflowInstanceService;
import com.flowforge.engine.dto.WorkflowInstanceResponse;
import com.flowforge.report.dto.AuditEventResponse;
import com.flowforge.report.dto.DashboardResponse;
import com.flowforge.task.TaskService;
import com.flowforge.task.dto.TaskFilter;
import com.flowforge.task.dto.TaskResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reporting reads: a user's own dashboard (Requirements 20.1, 20.2, 20.3).
 *
 * <h2>Composition rather than re-query</h2>
 * <p>The pending-task list comes from {@link TaskService} and the submitted-request list from
 * {@link WorkflowInstanceService}, because both already define what those rows look like — including
 * the walk from a task to its workflow's name, and the decision to withhold request payloads from
 * listings. Re-querying here would give the product two definitions of the same row that could drift
 * apart, and the dashboard is precisely where a reader would notice a task shown differently from how
 * the task list shows it.
 *
 * <h2>Scoping</h2>
 * <p>Every method takes the caller's id and every query is filtered by it. There is no parameter for
 * asking about another user, which is what makes {@code GET /api/reports/dashboard} safe for any
 * authenticated role: an ADMIN calling it sees their own dashboard, not everybody's.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportService {

    /** How many audit events the dashboard's activity feed carries (Requirement 20.3). */
    public static final int ACTIVITY_FEED_SIZE = 20;

    private final TaskService taskService;
    private final WorkflowInstanceService instanceService;
    private final AuditLogRepository auditLogRepository;

    /**
     * Everything a user's dashboard shows (Requirements 20.1, 20.2, 20.3).
     *
     * <h3>What counts as "pending that user's action"</h3>
     * <p>Any task assigned to them whose status is still open — {@code PENDING}, {@code DELEGATED} or
     * {@code ESCALATED}, as {@link com.flowforge.task.TaskStatus#isOpen()} defines it. Delegation and
     * escalation both move {@code assigned_to} to the new holder, so a task in either of those statuses
     * that is assigned to this user is work this user owes; narrowing to {@code PENDING} alone would
     * hide exactly the tasks that arrived because somebody else went on leave or ran out of time.
     *
     * <h3>Which submitted requests</h3>
     * <p>All of them, in every status, newest first (Requirement 20.2 asks for the status, which implies
     * showing finished requests too rather than only live ones).
     *
     * @param userId the caller, always the authenticated principal
     * @return the caller's dashboard
     * @throws AppException 401 when there is no authenticated caller
     */
    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(UUID userId) {
        UUID caller = requireCaller(userId);

        List<TaskResponse> pending = taskService.listTasks(caller, TaskFilter.none()).stream()
                .filter(task -> task.status() != null && task.status().isOpen())
                .toList();

        List<WorkflowInstanceResponse> submitted = instanceService.listMyInstances(caller);
        List<AuditEventResponse> activity = recentActivity(caller);

        log.debug("Dashboard for user {}: {} pending task(s), {} submitted request(s), {} event(s)",
                caller, pending.size(), submitted.size(), activity.size());

        return new DashboardResponse(pending.size(), pending, submitted, activity);
    }

    /**
     * The {@value #ACTIVITY_FEED_SIZE} most recent audit events involving a user (Requirement 20.3).
     *
     * <p>"Related to that user" is read as the union of two things: events the user performed, and
     * events recorded against the user's own account. The second half matters because an administrator
     * deactivating an account or changing a role is an event about that user in which they are not the
     * actor, and an actor-only feed would leave them the last to know.
     *
     * <p>Events on entities the user merely participates in — a comment on a request they initiated,
     * say — are deliberately not folded in. Finding them means fanning out over every instance and task
     * id the user touches, on every dashboard load, and the request detail view already shows a
     * request's own history.
     *
     * <p>Both halves are read at the feed's size and merged, so the result is the newest
     * {@value #ACTIVITY_FEED_SIZE} of the union: an entry that is both (the user deactivating
     * themselves) appears once, since the merge is keyed on the entry id.
     */
    private List<AuditEventResponse> recentActivity(UUID userId) {
        Map<UUID, AuditLog> merged = new LinkedHashMap<>();
        auditLogRepository.findTop20ByActorIdOrderByCreatedAtDesc(userId)
                .forEach(entry -> merged.put(entry.getId(), entry));
        auditLogRepository
                .findTop20ByEntityTypeAndEntityIdOrderByCreatedAtDesc(AuditLogService.ENTITY_USER, userId)
                .forEach(entry -> merged.put(entry.getId(), entry));

        List<AuditLog> ordered = new ArrayList<>(merged.values());
        ordered.sort(Comparator
                .comparing(AuditLog::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                // A total order, so two entries written in the same transaction — and therefore
                // carrying the same timestamp — do not swap places between two calls.
                .thenComparing(AuditLog::getId, Comparator.nullsLast(Comparator.naturalOrder())));

        return ordered.stream()
                .limit(ACTIVITY_FEED_SIZE)
                .map(ReportService::toResponse)
                .toList();
    }

    private static AuditEventResponse toResponse(AuditLog entry) {
        return new AuditEventResponse(
                entry.getId(),
                entry.getActorId(),
                entry.getAction(),
                entry.getEntityType(),
                entry.getEntityId(),
                entry.getCreatedAt());
    }

    /**
     * The principal is a {@code UUID} resolved from a verified token, so a null one means the endpoint
     * was reached without authentication — a wiring fault, not a caller error.
     */
    private UUID requireCaller(UUID userId) {
        if (userId == null) {
            throw new AppException("Authentication required", HttpStatus.UNAUTHORIZED);
        }
        return userId;
    }
}
