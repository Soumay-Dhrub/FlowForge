package com.flowforge.task;

import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.FileSizeLimitException;
import com.flowforge.common.exception.UnsupportedMediaTypeException;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.engine.WorkflowInstanceRepository;
import com.flowforge.task.dto.AttachmentResponse;
import com.flowforge.user.Role;
import com.flowforge.user.RoleRepository;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import com.flowforge.workflow.WorkflowVersion;
import com.flowforge.workflow.WorkflowVersionRepository;
import com.flowforge.workflow.Workflow;
import com.flowforge.workflow.WorkflowRepository;
import com.flowforge.workflow.WorkflowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * File attachments against a real PostgreSQL database (Requirements 14.1, 14.2, 14.3).
 *
 * <p>The unit and property tests drive {@code AttachmentService} over map-backed repositories, which
 * cannot show whether the {@code attachments} row Hibernate builds is one PostgreSQL will accept. That is
 * the risk worth covering here: {@code ddl-auto: validate} means booting this context at all proves the
 * entity matches the Flyway schema, and committing a row proves the column lengths, the {@code NOT NULL}s
 * and the two foreign keys are satisfied by what the service actually writes.
 *
 * <p>Assertions read state back through the repository rather than trusting the returned object, and the
 * bytes are checked on disk under the configured root — the metadata row and the file are two separate
 * effects and either could exist without the other.
 *
 * <p>Validates: Requirements 14.1, 14.2, 14.3.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers
class AttachmentIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("flowforge_test")
            .withUsername("flowforge")
            .withPassword("flowforge");

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // A throwaway root, so the test writes real files without leaving them in the working tree.
        registry.add("app.attachment.storage-path", () -> temporaryStorageRoot().toString());
    }

    private static Path storageRoot;

    private static synchronized Path temporaryStorageRoot() {
        if (storageRoot == null) {
            try {
                storageRoot = Files.createTempDirectory("flowforge-attachments-it");
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
        return storageRoot;
    }

    @Autowired
    private AttachmentService attachmentService;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private AttachmentStorage storage;

    @Autowired
    private WorkflowInstanceRepository instanceRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowVersionRepository versionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private User initiator;
    private WorkflowInstance instance;

    @BeforeEach
    void seedRequest() {
        Role admin = roleRepository.findByName("ADMIN").orElseThrow();
        initiator = userRepository.save(User.builder()
                .name("Ada Lovelace")
                .email("ada+" + UUID.randomUUID() + "@example.com")
                .passwordHash("not-a-real-hash")
                .role(admin)
                .isActive(true)
                .build());

        Workflow workflow = workflowRepository.save(Workflow.builder()
                .name("Expense Claim " + UUID.randomUUID())
                .status(WorkflowStatus.ACTIVE)
                .createdBy(initiator)
                .build());
        WorkflowVersion version = versionRepository.save(WorkflowVersion.builder()
                .workflow(workflow)
                .versionNumber(1)
                .graphJson(WorkflowVersion.emptyGraph())
                .isPublished(true)
                .isCurrent(true)
                .build());
        instance = instanceRepository.save(WorkflowInstance.builder()
                .workflowVersion(version)
                .initiatedBy(initiator)
                .requestData(new LinkedHashMap<>())
                .branchStatus(new LinkedHashMap<>())
                .build());
    }

    @Test
    void anAcceptedUploadCommitsItsMetadataAndItsBytes() throws Exception {
        byte[] body = InMemoryAttachmentFixture.bodyOf("application/pdf", 2048);

        AttachmentResponse stored = attachmentService.upload(
                instance.getId(),
                new MockMultipartFile("file", "receipt.pdf", "application/pdf", body),
                initiator.getId());

        Attachment persisted = attachmentRepository.findById(stored.id()).orElseThrow();
        assertThat(persisted.getFileName()).isEqualTo("receipt.pdf");
        assertThat(persisted.getContentType()).isEqualTo("application/pdf");
        assertThat(persisted.getFileSize()).isEqualTo(2048L);
        assertThat(persisted.instanceId()).isEqualTo(instance.getId());
        assertThat(persisted.uploaderId()).isEqualTo(initiator.getId());
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(persisted.getStoragePath())
                .as("the path is stored relative to the root, not as an absolute host path")
                .startsWith(instance.getId().toString() + "/")
                .doesNotContain("..");

        Path onDisk = storage.resolveWithin(persisted.getStoragePath());
        assertThat(onDisk).startsWith(storage.root());
        assertThat(Files.readAllBytes(onDisk))
                .as("the committed row points at exactly the bytes that were uploaded")
                .isEqualTo(body);

        assertThat(attachmentRepository.findByInstance_IdOrderByCreatedAtAsc(instance.getId()))
                .extracting(Attachment::getId)
                .containsExactly(stored.id());

        Files.deleteIfExists(onDisk);
    }

    @Test
    void aFileNameThatTriesToTraverseIsStoredUnderTheRootAndKeptAsPlainText() throws Exception {
        AttachmentResponse stored = attachmentService.upload(
                instance.getId(),
                new MockMultipartFile("file", "../../../../etc/flowforge-pwned.png",
                        "image/png", InMemoryAttachmentFixture.bodyOf("image/png", 64)),
                initiator.getId());

        Attachment persisted = attachmentRepository.findById(stored.id()).orElseThrow();
        assertThat(persisted.getFileName()).isEqualTo("flowforge-pwned.png");

        Path onDisk = storage.resolveWithin(persisted.getStoragePath());
        assertThat(onDisk.normalize()).startsWith(storage.root());
        assertThat(Files.exists(onDisk)).isTrue();
        assertThat(Files.exists(Path.of("/etc/flowforge-pwned.png"))).isFalse();

        Files.deleteIfExists(onDisk);
    }

    @Test
    void anOversizedUploadIsRefusedAndNothingIsCommitted() {
        long limit = storage.maxSizeBytes();
        byte[] tooBig = InMemoryAttachmentFixture.bodyOf("application/pdf", (int) limit + 1);

        assertThatThrownBy(() -> attachmentService.upload(
                instance.getId(),
                new MockMultipartFile("file", "huge.pdf", "application/pdf", tooBig),
                initiator.getId()))
                .isInstanceOf(FileSizeLimitException.class)
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);

        assertThat(attachmentRepository.findByInstance_IdOrderByCreatedAtAsc(instance.getId()))
                .isEmpty();
    }

    @Test
    void aDisallowedTypeIsRefusedAndNothingIsCommitted() {
        assertThatThrownBy(() -> attachmentService.upload(
                instance.getId(),
                new MockMultipartFile("file", "run.sh", "application/x-sh", "#!/bin/sh\n".getBytes()),
                initiator.getId()))
                .isInstanceOf(UnsupportedMediaTypeException.class)
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

        assertThat(attachmentRepository.findByInstance_IdOrderByCreatedAtAsc(instance.getId()))
                .isEmpty();
    }

    @Test
    void aNonParticipantCannotAttachToSomeoneElsesRequest() {
        Role admin = roleRepository.findByName("ADMIN").orElseThrow();
        User outsider = userRepository.save(User.builder()
                .name("Blaise Pascal")
                .email("blaise+" + UUID.randomUUID() + "@example.com")
                .passwordHash("not-a-real-hash")
                .role(admin)
                .isActive(true)
                .build());

        assertThatThrownBy(() -> attachmentService.upload(
                instance.getId(),
                new MockMultipartFile("file", "nosy.pdf", "application/pdf",
                        InMemoryAttachmentFixture.bodyOf("application/pdf", 64)),
                outsider.getId()))
                .isInstanceOf(AppException.class)
                .extracting(thrown -> ((AppException) thrown).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(attachmentRepository.findByInstance_IdOrderByCreatedAtAsc(instance.getId()))
                .isEmpty();
    }
}
