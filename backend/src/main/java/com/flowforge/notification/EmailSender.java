package com.flowforge.notification;

import java.util.Map;

/**
 * Implementations must not throw. Email is a side channel, so a refused SMTP connection must not roll
 * back the decision, escalation or assignment that triggered it. Best effort, logged on failure.
 */
public interface EmailSender {

    void send(String to, String subject, String body);

    void send(String to, String subject, String templateName, Map<String, Object> variables);
}
