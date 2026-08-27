package com.flowforge.task;

import com.flowforge.audit.AuditLogService;
import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.EntityNotFoundException;
import com.flowforge.common.exception.FileSizeLimitException;
import com.flowforge.common.exception.UnsupportedMediaTypeException;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.task.dto.AttachmentResponse;
import com.flowforge.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.util.UUID;

import static com.flowforge.task.InMemoryAttachmentFixture.DOCX;
import static com.flowforge.task.InMemoryAttachmentFixture.MAX_SIZE_BYTES;
import static com.flowforge.task.InMemoryAttachmentFixture.PDF;
import static com.flowforge.task.InMemoryAttachmentFixture.PNG;
import static com.flowforge.task.InMemoryAttachmentFixture.bodyOf;
import static com.flowforge.task.InMemoryAttachmentFixture.spoofedUpload;
import static com.flowforge.task.InMemoryAttachmentFixture.understatedUpload;
import static com.flowforge.task.InMemoryAttachmentFixture.upload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentServiceTest {

    @Test
    void anAllowedFileFromAParticipantIsStoredWithItsMetadata() {
        try (InMemoryAttachmentFixture fixture = new InMemoryAttachmentFixture()) {
            User initiator = fixture.user("Ada Lovelace", "EMPLOYEE");
            WorkflowInstance instance = fixture.instance(initiator);

            AttachmentResponse stored = fixture.attachmentService.upload(
                    instance.getId(), upload("invoice.pdf", PDF, 1024), initiator.getId());

            assertThat(stored.fileName()).isEqualTo("invoice.pdf");
            assertThat(stored.contentType()).isEqualTo(PDF);
            assertThat(stored.fileSize()).isEqualTo(1024);
            assertThat(stored.uploadedById()).isEqualTo(initiator.getId());
            assertThat(stored.instanceId()).isEqualTo(instance.getId());

            assertThat(fixture.storedFiles())
                    .as("the bytes are on disk exactly once")
                    .hasSize(1);
            assertThat(fixture.auditEntriesWithAction(AuditLogService.ACTION_UPLOAD_ATTACHMENT))
                    .as("Requirement 19.1: the upload is on the record")
                    .hasSize(1);
        }
    }

    @Test
    void theAssigneeOfATaskOnTheRequestIsAParticipantToo() {
        try (InMemoryAttachmentFixture fixture = new InMemoryAttachmentFixture()) {
            User initiator = fixture.user("Ada Lovelace", "EMPLOYEE");
            User approver = fixture.user("Grace Hopper", "MANAGER");
            WorkflowInstance instance = fixture.instance(initiator);
            fixture.task(instance, approver, TaskStatus.PENDING);

            AttachmentResponse stored = fixture.attachmentService.upload(
                    instance.getId(), upload("evidence.png", PNG, 256), approver.getId());

            assertThat(stored.uploadedById()).isEqualTo(approver.getId());
        }
    }

    @Test
    void aStrangerToTheRequestIsRefusedBeforeAnyBytesAreWritten() {
        try (InMemoryAttachmentFixture fixture = new InMemoryAttachmentFixture()) {
            User initiator = fixture.user("Ada Lovelace", "EMPLOYEE");
            User outsider = fixture.user("Blaise Pascal", "ADMIN");
            WorkflowInstance instance = fixture.instance(initiator);

            assertThatThrownBy(() -> fixture.attachmentService.upload(
                    instance.getId(), upload("nosy.pdf", PDF, 128), outsider.getId()))
                    .isInstanceOf(AppException.class)
                    .extracting(thrown -> ((AppException) thrown).getStatus())
                    .isEqualTo(HttpStatus.FORBIDDEN);

            assertThat(fixture.storedFiles()).isEmpty();
            assertThat(fixture.attachmentsById).isEmpty();
        }
    }

    @Test
    void anUnknownRequestIsA404() {
        try (InMemoryAttachmentFixture fixture = new InMemoryAttachmentFixture()) {
            User someone = fixture.user("Ada Lovelace", "EMPLOYEE");

            assertThatThrownBy(() -> fixture.attachmentService.upload(
                    UUID.randomUUID(), upload("invoice.pdf", PDF, 128), someone.getId()))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    /**
     * The traversal case, stated plainly: a file name that climbs out of the storage root must not be
     * able to place bytes anywhere but under it.
     */
    @Test
    void aTraversingFileNameCannotWriteOutsideTheStorageRoot() {
        try (InMemoryAttachmentFixture fixture = new InMemoryAttachmentFixture()) {
            User initiator = fixture.user("Ada Lovelace", "EMPLOYEE");
            WorkflowInstance instance = fixture.instance(initiator);

            AttachmentResponse stored = fixture.attachmentService.upload(
                    instance.getId(),
                    upload("../../../../../../tmp/flowforge-pwned.pdf", PDF, 64),
                    initiator.getId());

            assertThat(stored.fileName())
                    .as("the name is kept for display, but reduced to a bare file name")
                    .isEqualTo("flowforge-pwned.pdf")
                    .doesNotContain("..", "/");

            Path root = fixture.storage.root();
            assertThat(fixture.storedFiles())
                    .hasSize(1)
                    .allSatisfy(path -> assertThat(path.normalize()).startsWith(root));

            Attachment saved = fixture.attachmentsById.values().iterator().next();
            assertThat(saved.getStoragePath())
                    .as("the stored name is generated, not the client's")
                    .doesNotContain("..")
                    .doesNotContain("flowforge-pwned")
                    .endsWith(".pdf");
            assertThat(fixture.storage.resolveWithin(saved.getStoragePath())).startsWith(root);
        }
    }

    @Test
    void aWindowsStyleFullPathIsReducedToItsFileName() {
        assertThat(AttachmentStorage.sanitiseFileName("C:\\Users\\ada\\Desktop\\report.docx"))
                .isEqualTo("report.docx");
        assertThat(AttachmentStorage.sanitiseFileName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(AttachmentStorage.sanitiseFileName("..")).isEqualTo("attachment");
        assertThat(AttachmentStorage.sanitiseFileName("  ")).isEqualTo("attachment");
        assertThat(AttachmentStorage.sanitiseFileName(null)).isEqualTo("attachment");
        assertThat(AttachmentStorage.sanitiseFileName("re\u0000port\npdf"))
                .as("control characters are dropped rather than stored")
                .isEqualTo("reportpdf");
    }

    @Test
    void anEmptyUploadIsRefusedAsABadRequest() {
        try (InMemoryAttachmentFixture fixture = new InMemoryAttachmentFixture()) {
            User initiator = fixture.user("Ada Lovelace", "EMPLOYEE");
            WorkflowInstance instance = fixture.instance(initiator);

            assertThatThrownBy(() -> fixture.attachmentService.upload(
                    instance.getId(),
                    new MockMultipartFile("file", "empty.pdf", PDF, new byte[0]),
                    initiator.getId()))
                    .isInstanceOf(AppException.class)
                    .extracting(thrown -> ((AppException) thrown).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * The case a pre-check on {@code Content-Length} cannot catch: a part that claims to be small and
     * then streams past the limit. Only counting bytes while writing refuses this.
     */
    @Test
    void anUploadThatUnderstatesItsSizeIsStillRefusedAndLeavesNothingBehind() {
        try (InMemoryAttachmentFixture fixture = new InMemoryAttachmentFixture()) {
            User initiator = fixture.user("Ada Lovelace", "EMPLOYEE");
            WorkflowInstance instance = fixture.instance(initiator);

            assertThatThrownBy(() -> fixture.attachmentService.upload(
                    instance.getId(),
                    understatedUpload(PDF, 10, (int) MAX_SIZE_BYTES + 5_000),
                    initiator.getId()))
                    .isInstanceOf(FileSizeLimitException.class)
                    .extracting(thrown -> ((AppException) thrown).getStatus())
                    .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);

            assertThat(fixture.storedFiles())
                    .as("the partial file is removed when the write is abandoned")
                    .isEmpty();
            assertThat(fixture.attachmentsById).isEmpty();
        }
    }

    @Test
    void bytesThatContradictTheDeclaredTypeAreRefused() {
        try (InMemoryAttachmentFixture fixture = new InMemoryAttachmentFixture()) {
            User initiator = fixture.user("Ada Lovelace", "EMPLOYEE");
            WorkflowInstance instance = fixture.instance(initiator);

            assertThatThrownBy(() -> fixture.attachmentService.upload(
                    instance.getId(), spoofedUpload("actually-a-png.pdf", PDF, PNG),
                    initiator.getId()))
                    .isInstanceOf(UnsupportedMediaTypeException.class)
                    .extracting(thrown -> ((AppException) thrown).getStatus())
                    .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

            assertThat(fixture.storedFiles()).isEmpty();
        }
    }

    @Test
    void aScriptClaimingToBeAPdfIsRefused() {
        try (InMemoryAttachmentFixture fixture = new InMemoryAttachmentFixture()) {
            User initiator = fixture.user("Ada Lovelace", "EMPLOYEE");
            WorkflowInstance instance = fixture.instance(initiator);
            byte[] script = "<script>alert(1)</script>".getBytes();

            assertThatThrownBy(() -> fixture.attachmentService.upload(
                    instance.getId(),
                    new MockMultipartFile("file", "invoice.pdf", PDF, script),
                    initiator.getId()))
                    .isInstanceOf(UnsupportedMediaTypeException.class);

            assertThat(fixture.storedFiles()).isEmpty();
        }
    }

    @Test
    void aTypeCarryingParametersIsAcceptedOnItsMediaType() {
        try (InMemoryAttachmentFixture fixture = new InMemoryAttachmentFixture()) {
            User initiator = fixture.user("Ada Lovelace", "EMPLOYEE");
            WorkflowInstance instance = fixture.instance(initiator);

            AttachmentResponse stored = fixture.attachmentService.upload(
                    instance.getId(),
                    new MockMultipartFile("file", "contract.docx",
                            DOCX + "; charset=binary", bodyOf(DOCX, 300)),
                    initiator.getId());

            assertThat(stored.contentType()).isEqualTo(DOCX);
        }
    }

    @Test
    void attachmentsAreListedOldestFirstForParticipantsOnly() {
        try (InMemoryAttachmentFixture fixture = new InMemoryAttachmentFixture()) {
            User initiator = fixture.user("Ada Lovelace", "EMPLOYEE");
            User outsider = fixture.user("Blaise Pascal", "MANAGER");
            WorkflowInstance instance = fixture.instance(initiator);

            fixture.attachmentService.upload(
                    instance.getId(), upload("first.pdf", PDF, 64), initiator.getId());
            fixture.attachmentService.upload(
                    instance.getId(), upload("second.png", PNG, 64), initiator.getId());

            assertThat(fixture.attachmentService.listAttachments(instance.getId(), initiator.getId()))
                    .extracting(AttachmentResponse::fileName)
                    .containsExactly("first.pdf", "second.png");

            assertThatThrownBy(() ->
                    fixture.attachmentService.listAttachments(instance.getId(), outsider.getId()))
                    .isInstanceOf(AppException.class)
                    .extracting(thrown -> ((AppException) thrown).getStatus())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }
}
