package com.flowforge.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Spring Mail implementation of {@link EmailSender}.
 *
 * <p>Delivery is best effort. If no {@link JavaMailSender} is present (mail auto-configuration
 * switched off) or the SMTP host refuses the message, the failure is logged and swallowed: the
 * caller's transaction — creating a reset token, recording a notification — must still commit.
 * In that log-only mode the recipient and subject are recorded so a developer can see that a
 * message would have gone out; message bodies are not logged because they can carry reset links.</p>
 */
@Component
@Slf4j
public class SpringMailEmailSender implements EmailSender {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String fromAddress;

    public SpringMailEmailSender(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${app.mail.from:no-reply@flowforge.local}") String fromAddress
    ) {
        this.mailSenderProvider = mailSenderProvider;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(String to, String subject, String body) {
        if (to == null || to.isBlank()) {
            return;
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("Mail transport not configured; skipped email '{}' to {}", subject, to);
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);

        try {
            mailSender.send(message);
            log.debug("Sent email '{}' to {}", subject, to);
        } catch (MailException ex) {
            log.warn("Failed to send email '{}' to {}: {}", subject, to, ex.getMessage());
        }
    }
}
