package com.flowforge.notification.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.Map;

/**
 * A change to the caller's email-delivery preferences (Requirement 18.2).
 *
 * <p>A map from event type to on/off rather than a full replacement list, so a client can flip one
 * switch without having to send back the state of every other one — sending all of them is how a stale
 * screen silently reverts a change made in another tab. Event types not named here keep whatever they
 * had, including "no stored choice at all".
 *
 * @param preferences event type to whether email is enabled; must name at least one event, and every
 *                    key must be an emailable event type
 */
public record UpdateNotificationPreferencesRequest(
        @NotEmpty(message = "name at least one event type") Map<String, Boolean> preferences
) {
}
