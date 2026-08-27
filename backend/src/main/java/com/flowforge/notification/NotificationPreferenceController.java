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

@RestController
@RequestMapping("/api/users/me/notification-preferences")
@RequiredArgsConstructor
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferenceService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<NotificationPreferenceResponse>>> getPreferences(
            @AuthenticationPrincipal UUID callerId
    ) {
        return ResponseEntity.ok(ApiResponse.success(preferenceService.listForUser(callerId)));
    }

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
