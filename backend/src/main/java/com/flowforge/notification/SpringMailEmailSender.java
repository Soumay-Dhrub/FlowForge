package com.flowforge.notification;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Spring Mail implementation of {@link EmailSender}, rendering templates with Thymeleaf
 * (Requirement 17.4).
 *
 * <h2>Best effort, by design</h2>
 * <p>Nothing here throws. If no {@link JavaMailSender} is present (mail auto-configuration switched
 * off), if the SMTP host refuses the message, or if a template fails to render, the failure is logged
 * and swallowed — the transaction that caused the notification has already committed and must not be
 * disturbed by a dead relay. {@link Exception} is caught rather than {@code MailException} alone
 * because template rendering and MIME assembly fail with their own types, and from the caller's point
 * of view "the mail did not go" is one outcome however it happened.
 *
 * <p>In log-only mode the recipient and subject are recorded so a developer can see that a message
 * would have gone out. Bodies are never logged: the password-reset body carries a working reset link.
 */
@Component
@Slf4j
public class SpringMailEmailSender implements EmailSender {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final SpringTemplateEngine templateEngine;
    private final String fromAddress;

    public SpringMailEmailSender(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            SpringTemplateEngine templateEngine,
            @Value("${app.mail.from:no-reply@flowforge.local}") String fromAddress
    ) {
        this.mailSenderProvider = mailSenderProvider;
        this.templateEngine = templateEngine;
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
        } catch (Exception failure) {
            log.warn("Failed to send email '{}' to {}: {}", subject, to, failure.getMessage());
        }
    }

    @Override
    public void send(String to, String subject, String templateName, Map<String, Object> variables) {
        if (to == null || to.isBlank()) {
            return;
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("Mail transport not configured; skipped '{}' email '{}' to {}",
                    templateName, subject, to);
            return;
        }

        try {
            // Rendered before the message is opened, so a broken template costs nothing but a log line.
            String html = render(templateName, variables);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);

            mailSender.send(message);
            log.debug("Sent '{}' email '{}' to {}", templateName, subject, to);
        } catch (Exception failure) {
            log.warn("Failed to send '{}' email '{}' to {}: {}",
                    templateName, subject, to, failure.getMessage());
        }
    }

    /**
     * Render one template.
     *
     * <p>Package-private so the escaping guarantee can be tested directly against the real engine,
     * without SMTP: that a workflow name or a comment containing markup comes out as text is the one
     * property of this class where a mistake ships live HTML to an inbox.
     *
     * @param templateName template name without suffix, e.g. {@code email/task-assigned}
     * @param variables    values the template reads; {@code null} treated as empty
     * @return the rendered HTML
     */
    String render(String templateName, Map<String, Object> variables) {
        Context context = new Context();
        if (variables != null) {
            context.setVariables(variables);
        }
        return templateEngine.process(templateName, context);
    }
}
