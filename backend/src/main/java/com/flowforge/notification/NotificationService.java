package com.flowforge.notification;

import java.util.Map;
import java.util.UUID;

public interface NotificationService {

    Notification notify(UUID userId, String eventType, Map<String, Object> payload);
}
