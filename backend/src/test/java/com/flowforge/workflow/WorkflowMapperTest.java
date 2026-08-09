package com.flowforge.workflow;

import com.flowforge.user.User;
import com.flowforge.workflow.dto.WorkflowResponse;
import com.flowforge.workflow.dto.WorkflowVersionResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WorkflowMapper} using the MapStruct-generated implementation.
 */
class WorkflowMapperTest {

    private final WorkflowMapper mapper = new WorkflowMapperImpl(new WorkflowVersionMapperImpl());

    @Test
    void toResponse_flattensAuthorAndOmitsVersionHistory() {
        User author = User.builder().id(UUID.randomUUID()).name("Grace Hopper").build();
        Workflow workflow = Workflow.builder()
                .id(UUID.randomUUID())
                .name("Leave Request")
                .description("Annual leave approval chain")
                .status(WorkflowStatus.ACTIVE)
                .createdBy(author)
                .build();
        workflow.addVersion(WorkflowVersion.builder().versionNumber(1).build());

        WorkflowResponse response = mapper.toResponse(workflow);

        assertThat(response.id()).isEqualTo(workflow.getId());
        assertThat(response.name()).isEqualTo("Leave Request");
        assertThat(response.description()).isEqualTo("Annual leave approval chain");
        assertThat(response.status()).isEqualTo(WorkflowStatus.ACTIVE);
        assertThat(response.createdById()).isEqualTo(author.getId());
        assertThat(response.createdByName()).isEqualTo("Grace Hopper");
        assertThat(response.versions()).isNull();
    }

    @Test
    void toDetailResponse_includesOrderedVersionHistory() {
        Workflow workflow = Workflow.builder()
                .id(UUID.randomUUID())
                .name("Purchase Order")
                .createdBy(User.builder().id(UUID.randomUUID()).name("Alan Turing").build())
                .build();
        workflow.addVersion(WorkflowVersion.builder().id(UUID.randomUUID()).versionNumber(1).isPublished(true).build());
        workflow.addVersion(WorkflowVersion.builder().id(UUID.randomUUID()).versionNumber(2).build());

        WorkflowResponse response = mapper.toDetailResponse(workflow);

        assertThat(response.versions()).extracting(WorkflowVersionResponse::versionNumber).containsExactly(1, 2);
        assertThat(response.versions().getFirst().workflowId()).isEqualTo(workflow.getId());
        assertThat(response.versions().getFirst().isPublished()).isTrue();
        assertThat(response.versions().get(1).isPublished()).isFalse();
    }

    @Test
    void toResponseList_defaultsToTheSummaryMappingForEveryRow() {
        Workflow first = Workflow.builder()
                .id(UUID.randomUUID())
                .name("First")
                .createdBy(User.builder().id(UUID.randomUUID()).name("Author One").build())
                .build();
        first.addVersion(WorkflowVersion.builder().versionNumber(1).build());
        Workflow second = Workflow.builder()
                .id(UUID.randomUUID())
                .name("Second")
                .status(WorkflowStatus.ARCHIVED)
                .createdBy(User.builder().id(UUID.randomUUID()).name("Author Two").build())
                .build();

        List<WorkflowResponse> responses = mapper.toResponseList(List.of(first, second));

        assertThat(responses).extracting(WorkflowResponse::name).containsExactly("First", "Second");
        assertThat(responses).extracting(WorkflowResponse::status)
                .containsExactly(WorkflowStatus.DRAFT, WorkflowStatus.ARCHIVED);
        assertThat(responses).allSatisfy(response -> assertThat(response.versions()).isNull());
    }
}
