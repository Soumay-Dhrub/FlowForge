package com.flowforge.notification;

import com.flowforge.common.response.ApiResponse;
import com.flowforge.notification.dto.NotificationPreferenceResponse;
import com.flowforge.notification.dto.UpdateNotificationPreferencesRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * A user's own email-delivery preferences (Requirement 18.2).
 *
 * <h2>Authorization</h2>
 * <p>Any authenticated user, and only for themselves. Like {@code /api/users/me}, the subject is the
 * principal the JWT filter resolved and there is no path or query parameter naming a user — so there is
 * no request a caller can construct that reads or writes somebody else's settings. That is a stronger
 * guarantee than comparing an id to the caller's, because there is nothing to compare.
 *
 * <p>ADMIN is not privileged here either. An administrator editing another person's delivery choices
 * would be able to switch off the mail that tells that person their work is waiting, which is not an
 * administrative act — and Requirement 18.2 is written about each user controlling their own.
 */
@RestController
@RequestMapping("/api/users/me/notification-preferences")
@RequiredArgsConstructor
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferenceService;

    /**
     * The caller's switches, one per emailable event type.
     *
     * <p>Always the full set with effective values, including for a user who has never saved anything —
     * a screen cannot offer a toggle it was not told about, and a client should not have to hardcode
     * the platform's default.
     *
     * @param callerId the authenticated user
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<NotificationPreferenceResponse>>> getPreferences(
            @AuthenticationPrincipal UUID callerId
    ) {
        return ResponseEntity.ok(ApiResponse.success(preferenceService.listForUser(callerId)));
    }

    /**
     * Set email delivery for one or more event types (Requirement 18.2).
     *
     * <p>PUT rather than PATCH because each named event type is replaced outright — the value sent is
     * the value stored. Event types the body omits are untouched. An event type with no email template
     * is a 400: a switch that cannot take effect should not appear to.
     *
     * @param callerId the authenticated user
     * @param request  the event types to set, and what to set them to
     * @return the caller's full set of switches after the change
     */
    @PutMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<NotificationPreferenceResponse>>> updatePreferences(
            @AuthenticationPrincipal UUID callerId,
            @Valid @RequestBody UpdateNotificationPreferencesRequest request
    ) {
        List<NotificationPreferenceResponse> updated =
                preferenceService.update(callerId, request.preferences());
        return ResponseEntity.ok(ApiResponse.success("Notification preferences updated", updated));
    }
}
