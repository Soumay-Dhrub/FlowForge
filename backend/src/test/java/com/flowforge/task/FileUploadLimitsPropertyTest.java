package com.flowforge.task;

import com.flowforge.common.exception.AppException;
import com.flowforge.common.exception.FileSizeLimitException;
import com.flowforge.common.exception.UnsupportedMediaTypeException;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.task.dto.AttachmentResponse;
import com.flowforge.user.User;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

import static com.flowforge.task.InMemoryAttachmentFixture.DOC;
import static com.flowforge.task.InMemoryAttachmentFixture.DOCX;
import static com.flowforge.task.InMemoryAttachmentFixture.JPEG;
import static com.flowforge.task.InMemoryAttachmentFixture.MAX_SIZE_BYTES;
import static com.flowforge.task.InMemoryAttachmentFixture.PDF;
import static com.flowforge.task.InMemoryAttachmentFixture.PNG;
import static com.flowforge.task.InMemoryAttachmentFixture.bodyOf;
import static com.flowforge.task.InMemoryAttachmentFixture.upload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property 13: File Upload Enforces Size and Type Limits.
 *
 * <p>For any upload, exceeding the configured size limit must yield 413 and a type that is not on the
 * allowlist must yield 415 — and neither answer may depend on who is uploading. The uploader is
 * therefore generated as well as the file: their role (EMPLOYEE, MANAGER, ADMIN) and their relationship
 * to the request (initiator, or assignee of a task on it) vary independently of the file, because a
 * limit that a manager can talk their way past is not a limit.
 *
 * <p>Refusal is asserted as the absence of effects, not just as a status code. A 413 returned after the
 * bytes were written, or a 415 with a metadata row already saved, would satisfy the letter of
 * Requirements 14.2 and 14.3 while leaving exactly the file the platform said it would not take. So every
 * refusal also checks that the storage directory is empty and no attachment row exists.
 *
 * <p>Sizes are generated around the boundary rather than uniformly: the limit itself, one byte under and
 * one byte over are where an off-by-one lives, and a random 10 MB draw would almost never land there.
 * Types include both the disallowed kind (a plausible MIME type absent from the allowlist) and the
 * spoofed kind (an allowed type declared over bytes of a different one), since the second is what an
 * allowlist checked against the client's header alone would wave through.
 *
 * <p><b>When a file is both oversized and of a forbidden type the expected answer is 413.</b>
 * Requirement 14 does not order the two checks; this codifies the service's documented choice to answer
 * the cheap, header-only question first.
 *
 * <p><b>Validates: Requirements 14.1, 14.2, 14.3</b>
 */
@Tag("flowforge")
class FileUploadLimitsPropertyTest {

    @Property(tries = 100)
    @Label("Property 13: an oversized file is refused with 413 and stored nowhere, whoever uploads it")
    void oversizedUploadsAreRefusedWith413(
            @ForAll("allowedTypes") String type,
            @ForAll("oversizedBy") int excessBytes,
            @ForAll("uploaders") Uploader uploader
    ) {
        try (InMemoryAttachmentFixture fixture = new InMemoryAttachmentFixture()) {
            Participant participant = uploader.join(fixture);
            MultipartFile file = upload(
                    "supporting" + extensionOf(type), type, (int) MAX_SIZE_BYTES + excessBytes);

            assertThatThrownBy(() -> fixture.attachmentService.upload(
                    participant.instanceId(), file, participant.userId()))
                    .isInstanceOf(FileSizeLimitException.class)
                    .extracting(thrown -> ((AppException) thrown).getStatus())
                    .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);

            assertNothingWasKept(fixture);
        }
    }

    @Property(tries = 100)
    @Label("Property 13: a type outside the allowlist is refused with 415 and stored nowhere, "
            + "whoever uploads it")
    void disallowedTypesAreRefusedWith415(
            @ForAll("forbiddenTypes") String type,
            @ForAll("withinLimit") int size,
            @ForAll("uploaders") Uploader uploader
    ) {
        try (InMemoryAttachmentFixture fixture = new InMemoryAttachmentFixture()) {
            Participant participant = uploader.join(fixture);
            MultipartFile file = upload("payload.bin", type, size);

            assertThatThrownBy(() -> fixture.attachmentService.upload(
                    participant.instanceId(), file, participant.userId()))
                    .isInstanceOf(UnsupportedMediaTypeException.class)
                    .extracting(thrown -> ((AppException) thrown).getStatus())
                    .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

            assertNothingWasKept(fixture);
        }
    }

    @Property(tries = 100)
    @Label("Property 13: an allowed type declared over the bytes of another is refused with 415")
    void spoofedTypesAreRefusedWith415(
            @ForAll("distinctAllowedPairs") String[] declaredAndActual,
            @ForAll("withinLimit") int size,
            @ForAll("uploaders") Uploader uploader
    ) {
        try (InMemoryAttachmentFixture fixture = new InMemoryAttachmentFixture()) {
            Participant participant = uploader.join(fixture);
            String declared = declaredAndActual[0];
            String actual = declaredAndActual[1];
            MultipartFile file = new MockMultipartFile(
                    "file", "claimed" + extensionOf(declared), declared, bodyOf(actual, size));

            assertThatThrownBy(() -> fixture.attachmentService.upload(
                    participant.instanceId(), file, participant.userId()))
                    .isInstanceOf(UnsupportedMediaTypeException.class)
                    .extracting(thrown -> ((AppException) thrown).getStatus())
                    .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

            assertNothingWasKept(fixture);
        }
    }

    @Property(tries = 100)
    @Label("Property 13: a file within the limit and of an allowed type is accepted, whoever uploads it")
    void acceptableUploadsAreStored(
            @ForAll("allowedTypes") String type,
            @ForAll("withinLimit") int size,
            @ForAll("uploaders") Uploader uploader
    ) {
        try (InMemoryAttachmentFixture fixture = new InMemoryAttachmentFixture()) {
            Participant participant = uploader.join(fixture);
            String fileName = "supporting" + extensionOf(type);

            AttachmentResponse stored = fixture.attachmentService.upload(
                    participant.instanceId(), upload(fileName, type, size), participant.userId());

            assertThat(stored.contentType()).isEqualTo(type);
            assertThat(stored.fileName()).isEqualTo(fileName);
            assertThat(stored.uploadedById()).isEqualTo(participant.userId());
            assertThat(stored.fileSize())
                    .as("the recorded size is the byte count actually written")
                    .isEqualTo(Math.max(size, InMemoryAttachmentFixture.signatureOf(type).length))
                    .isLessThanOrEqualTo(MAX_SIZE_BYTES);
            assertThat(fixture.storedFiles()).hasSize(1);
            assertThat(fixture.attachmentsById).hasSize(1);
        }
    }

    @Property(tries = 100)
    @Label("Property 13: a file that is both oversized and of a forbidden type is refused, "
            + "and the size answer wins")
    void bothViolationsAtOnceAreRefused(
            @ForAll("forbiddenTypes") String type,
            @ForAll("oversizedBy") int excessBytes,
            @ForAll("uploaders") Uploader uploader
    ) {
        try (InMemoryAttachmentFixture fixture = new InMemoryAttachmentFixture()) {
            Participant participant = uploader.join(fixture);
            MultipartFile file = upload("payload.bin", type, (int) MAX_SIZE_BYTES + excessBytes);

            assertThatThrownBy(() -> fixture.attachmentService.upload(
                    participant.instanceId(), file, participant.userId()))
                    .isInstanceOf(FileSizeLimitException.class)
                    .extracting(thrown -> ((AppException) thrown).getStatus())
                    .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);

            assertNothingWasKept(fixture);
        }
    }

    /** A refusal leaves no bytes and no row — the part of "refused" a status code does not say. */
    private void assertNothingWasKept(InMemoryAttachmentFixture fixture) {
        assertThat(fixture.storedFiles()).as("nothing was written to storage").isEmpty();
        assertThat(fixture.attachmentsById).as("no metadata row was persisted").isEmpty();
    }

    private static String extensionOf(String type) {
        return switch (type) {
            case PDF -> ".pdf";
            case JPEG -> ".jpg";
            case PNG -> ".png";
            case DOC -> ".doc";
            case DOCX -> ".docx";
            default -> ".bin";
        };
    }

    // ── generators ───────────────────────────────────────────────────────────────────────────────

    /** Every type {@code application.yml} allows. */
    @Provide
    Arbitrary<String> allowedTypes() {
        return Arbitraries.of(PDF, JPEG, PNG, DOC, DOCX);
    }

    /**
     * Plausible types that are not on the allowlist — the ones a real client would actually try, rather
     * than random strings that would only ever exercise the "unknown" branch.
     */
    @Provide
    Arbitrary<String> forbiddenTypes() {
        return Arbitraries.of(
                "application/zip",
                "application/x-msdownload",
                "text/html",
                "image/svg+xml",
                "application/javascript",
                "application/octet-stream",
                "text/plain",
                "image/gif",
                "application/x-sh",
                "video/mp4");
    }

    /** An allowed type paired with a *different* allowed type to fake it with. */
    @Provide
    Arbitrary<String[]> distinctAllowedPairs() {
        return Combinators.combine(allowedTypes(), allowedTypes())
                .as((declared, actual) -> new String[]{declared, actual})
                .filter(pair -> !pair[0].equals(pair[1]))
                // DOC and DOCX are the one pair a signature check cannot separate from its own
                // container: both would need the archive's contents inspected, which this gate does
                // not claim to do. Excluded so the property states only what the code guarantees.
                .filter(pair -> !isWordPair(pair[0], pair[1]));
    }

    private static boolean isWordPair(String left, String right) {
        return (DOC.equals(left) || DOCX.equals(left)) && (DOC.equals(right) || DOCX.equals(right));
    }

    /** Sizes at and below the limit, weighted to the boundary. */
    @Provide
    Arbitrary<Integer> withinLimit() {
        return Arbitraries.oneOf(
                Arbitraries.of((int) MAX_SIZE_BYTES, (int) MAX_SIZE_BYTES - 1, 1, 8),
                Arbitraries.integers().between(1, (int) MAX_SIZE_BYTES));
    }

    /** How far past the limit an oversized file goes; one byte over is the interesting case. */
    @Provide
    Arbitrary<Integer> oversizedBy() {
        return Arbitraries.oneOf(
                Arbitraries.of(1, 2, 64),
                Arbitraries.integers().between(1, 20_000));
    }

    /** Who uploads: a role, and a way of being involved in the request. */
    @Provide
    Arbitrary<Uploader> uploaders() {
        return Combinators.combine(
                        Arbitraries.of("EMPLOYEE", "MANAGER", "ADMIN"),
                        Arbitraries.of(true, false),
                        Arbitraries.of(TaskStatus.PENDING, TaskStatus.COMPLETED, TaskStatus.ESCALATED))
                .as(Uploader::new);
    }

    /**
     * A generated uploader: their role, whether they are the initiator or an assignee, and — when an
     * assignee — the state of the task that makes them one.
     */
    record Uploader(String role, boolean isInitiator, TaskStatus taskStatus) {

        /** Build the request and return the participant who will upload to it. */
        Participant join(InMemoryAttachmentFixture fixture) {
            User initiator = fixture.user("Ada Lovelace", isInitiator ? role : "EMPLOYEE");
            WorkflowInstance instance = fixture.instance(initiator);
            if (isInitiator) {
                return new Participant(instance, initiator);
            }
            User assignee = fixture.user("Grace Hopper", role);
            fixture.task(instance, assignee, taskStatus);
            return new Participant(instance, assignee);
        }
    }

    /** A request and someone entitled to attach to it. */
    record Participant(WorkflowInstance instance, User user) {

        UUID instanceId() {
            return instance.getId();
        }

        UUID userId() {
            return user.getId();
        }
    }
}
