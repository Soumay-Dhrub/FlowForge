package com.flowforge.notification;

import com.flowforge.common.response.ApiResponse;
import com.flowforge.notification.dto.NotificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Notification endpoints — a user's own inbox (Requirements 17.1, 18.1, 18.3).
 *
 * <h2>Authorization</h2>
 * <p>Every endpoint is open to any authenticated user, because an inbox is self-service: everyone
 * has one. As with {@code /api/tasks}, the scoping rather than the role is what protects the data —
 * each method reads the caller's id from the verified token and there is no parameter for asking
 * about somebody else, so an ADMIN sees exactly their own notifications here (Requirement 3.1).
 *
 * <p>Marking read is checked on ownership in {@link NotificationInboxService#markRead}, which
 * refuses another user's notification with 403 and returns nothing about it. A privileged role does
 * not override that: read status records that <em>this</em> person saw the message, and a
 * notification's payload can carry a reviewer's comment on a request the caller is not party to.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationInboxService notificationInboxService;

    /**
     * The caller's notifications, unread first then newest (Requirement 18.3).
     *
     * @param callerId the authenticated user
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> listNotifications(
            @AuthenticationPrincipal UUID callerId
    ) {
        return ResponseEntity.ok(ApiResponse.success(notificationInboxService.listForUser(callerId)));
    }

    /**
     * How many of the caller's notifications are unread — the bell's badge.
     *
     * <p>Split out from the listing so a client polling for a number does not pull every payload to
     * get it.
     *
     * @param callerId the authenticated user
     */
    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Long>> unreadCount(@AuthenticationPrincipal UUID callerId) {
        return ResponseEntity.ok(ApiResponse.success(notificationInboxService.unreadCount(callerId)));
    }

    /**
     * Mark one notification read (Requirement 18.1).
     *
     * <p>Marking someone else's notification returns 403; an unknown id returns 404; marking one
     * that is already read succeeds and changes nothing.
     *
     * @param id       the notification to mark
     * @param callerId the authenticated user, who must be the recipient
     */
    @PatchMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<NotificationResponse>> markRead(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID callerId
    ) {
        NotificationResponse read = notificationInboxService.markRead(id, callerId);
        return ResponseEntity.ok(ApiResponse.success("Notification marked read", read));
    }
}
