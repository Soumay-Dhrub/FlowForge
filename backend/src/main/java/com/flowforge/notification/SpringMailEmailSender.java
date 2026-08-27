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

    String render(String templateName, Map<String, Object> variables) {
        Context context = new Context();
        if (variables != null) {
            context.setVariables(variables);
        }
        return templateEngine.process(templateName, context);
    }
}
