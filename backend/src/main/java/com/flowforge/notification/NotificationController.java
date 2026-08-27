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

    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Long>> unreadCount(@AuthenticationPrincipal UUID callerId) {
        return ResponseEntity.ok(ApiResponse.success(notificationInboxService.unreadCount(callerId)));
    }

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
