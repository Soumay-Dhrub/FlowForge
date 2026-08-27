package com.flowforge.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.common.exception.GlobalExceptionHandler;
import com.flowforge.common.response.ApiResponse;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("flowforge")
class RegistrationMissingFieldsPropertyTest {

    /** JSON field name → the DTO property that carries it. */
    private static final Map<String, String> REQUIRED_FIELDS = Map.of(
            "name", "name",
            "email", "email",
            "password", "password",
            "roleId", "roleId",
            "departmentId", "departmentId");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Property(tries = 100)
    @Label("Property 2: a registration payload missing any required field is rejected with 400 and creates nothing")
    void missingRequiredFieldsAreRejected(@ForAll("incompletePayloads") IncompletePayload payload) throws Exception {
        RecordingUserService userService = new RecordingUserService();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload.body())))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);

        ApiResponse<?> body = objectMapper.readValue(
                result.getResponse().getContentAsString(), ApiResponse.class);
        assertThat(body.success()).isFalse();

        List<String> reportedFields = body.errors().stream()
                .map(ApiResponse.FieldError::field)
                .toList();
        assertThat(reportedFields).containsAll(payload.omittedFields());

        // Nothing was created: the service was never reached.
        assertThat(userService.createdUsers).isEmpty();
    }

    record IncompletePayload(Map<String, Object> body, Set<String> omittedFields) {
    }

    @Provide
    Arbitrary<IncompletePayload> incompletePayloads() {
        Arbitrary<Set<String>> fieldsToBreak = Arbitraries.of(REQUIRED_FIELDS.keySet().toArray(new String[0]))
                .set().ofMinSize(1).ofMaxSize(REQUIRED_FIELDS.size());

        // Either drop the key entirely, or send a blank value where a value is expected.
        Arbitrary<Boolean> blankInsteadOfAbsent = Arbitraries.of(true, false);
        Arbitrary<String> blankValues = Arbitraries.of("", "   ", "\t");

        return Combinators.combine(fieldsToBreak, blankInsteadOfAbsent, blankValues)
                .as((broken, blank, blankValue) -> {
                    Map<String, Object> body = validBody();
                    for (String field : broken) {
                        if (blank && isTextField(field)) {
                            body.put(field, blankValue);
                        } else {
                            body.remove(field);
                        }
                    }
                    return new IncompletePayload(body, broken.stream()
                            .map(REQUIRED_FIELDS::get)
                            .collect(java.util.stream.Collectors.toSet()));
                });
    }

    /** UUID fields cannot carry a blank string, so only the text fields have a blank variant. */
    private boolean isTextField(String field) {
        return Set.of("name", "email", "password").contains(field);
    }

    private Map<String, Object> validBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Ada Lovelace");
        body.put("email", "ada@example.com");
        body.put("password", "correct-horse-battery");
        body.put("roleId", UUID.randomUUID().toString());
        body.put("departmentId", UUID.randomUUID().toString());
        return body;
    }
}
