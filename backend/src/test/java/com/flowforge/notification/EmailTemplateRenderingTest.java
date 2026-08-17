package com.flowforge.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * The four notification templates, rendered by the real Thymeleaf engine (Requirement 17.4).
 *
 * <p>Two things only this test can establish. First, that the templates exist and resolve — including
 * the shared fragments they pull in, which a mocked engine would never notice were missing. Second, and
 * the reason this test is not optional: <b>that variables are escaped</b>. A user's name, a workflow's
 * name and a comment are all free text that somebody else typed; if any of them reached an inbox as
 * markup, FlowForge would be a mail-borne HTML injection vector against its own users. The assertion is
 * on the rendered output, not on the template source, because "we remembered to use th:text" is not the
 * same claim.
 *
 * <p>Validates: Requirements 17.4.
 */
class EmailTemplateRenderingTest {

    /** Text that renders as markup if anything interpolates it unescaped. */
    private static final String HOSTILE_NAME =
            "<script>alert('xss')</script> & \"Fast\" <b>Track</b>";

    private SpringMailEmailSender sender;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);

        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);

        // No JavaMailSender: rendering is what is under test, and an absent transport is exactly the
        // configuration a developer runs locally.
        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.mail.javamail.JavaMailSender> noMailSender =
                mock(ObjectProvider.class);
        sender = new SpringMailEmailSender(noMailSender, templateEngine, "no-reply@flowforge.local");
    }

    @Test
    @DisplayName("Requirement 17.4: every catalogued event's template resolves and renders")
    void everyTemplateRenders() {
        for (EmailEventCatalog.EmailEvent event : EmailEventCatalog.all()) {
            String html = sender.render(event.templateName(), variables("Ada Lovelace"));

            assertThat(html)
                    .as("%s renders a document", event.templateName())
                    .contains("<html")
                    .contains("Ada Lovelace")
                    .contains("https://flowforge.test/tasks/");
        }
    }

    @Test
    @DisplayName("A name containing HTML is rendered as text, not as markup")
    void variablesAreEscaped() {
        for (EmailEventCatalog.EmailEvent event : EmailEventCatalog.all()) {
            String html = sender.render(event.templateName(), variables(HOSTILE_NAME));

            assertThat(html)
                    .as("%s must not emit a live script tag", event.templateName())
                    .doesNotContain("<script>")
                    .doesNotContain("<b>Track</b>");
            assertThat(html)
                    .as("%s escapes the angle brackets and ampersand instead", event.templateName())
                    .contains("&lt;script&gt;")
                    .contains("&amp;");
        }
    }

    @Test
    @DisplayName("An absent identifier drops its row rather than printing the word null")
    void absentValuesAreOmitted() {
        Map<String, Object> sparse = new LinkedHashMap<>();
        sparse.put("recipientName", "Ada Lovelace");
        sparse.put("taskId", null);
        sparse.put("instanceId", null);
        sparse.put("nodeId", null);
        sparse.put("dueAt", null);
        sparse.put("link", "https://flowforge.test/dashboard");

        String html = sender.render("email/task-escalated", sparse);

        assertThat(html).doesNotContain("null");
        assertThat(html).contains("https://flowforge.test/dashboard");
    }

    @Test
    @DisplayName("No name at all still addresses the reader rather than rendering an empty greeting")
    void aMissingNameFallsBack() {
        Map<String, Object> variables = variables(null);

        String html = sender.render("email/task-assigned", variables);

        assertThat(html).contains("Hello <span>there</span>");
    }

    /**
     * A template that cannot be rendered must not escape as an exception — the notification is already
     * committed and the caller is long gone.
     */
    @Test
    @DisplayName("An unknown template is logged and swallowed, not thrown")
    void anUnknownTemplateDoesNotThrowFromSend() {
        assertThatCode(() -> sender.send(
                "ada@flowforge.local", "Subject", "email/does-not-exist", Map.of()))
                .doesNotThrowAnyException();
    }

    private Map<String, Object> variables(String recipientName) {
        UUID taskId = UUID.randomUUID();
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("recipientName", recipientName);
        variables.put("eventType", NotificationEventTypes.TASK_ASSIGNED);
        variables.put("taskId", taskId.toString());
        variables.put("instanceId", UUID.randomUUID().toString());
        variables.put("nodeId", UUID.randomUUID().toString());
        variables.put("dueAt", "2024-06-01T10:00:00Z");
        variables.put("link", "https://flowforge.test/tasks/" + taskId);
        return variables;
    }
}
