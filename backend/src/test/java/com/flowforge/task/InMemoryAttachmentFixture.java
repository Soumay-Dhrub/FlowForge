package com.flowforge.task;

import com.flowforge.audit.AuditLog;
import com.flowforge.audit.AuditLogRepository;
import com.flowforge.audit.AuditLogService;
import com.flowforge.engine.WorkflowInstance;
import com.flowforge.engine.WorkflowInstanceRepository;
import com.flowforge.user.Role;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import com.flowforge.workflow.NodeType;
import com.flowforge.workflow.WorkflowNode;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class InMemoryAttachmentFixture implements AutoCloseable {

    /** The default limit these tests work against: small enough to exceed cheaply. */
    static final long MAX_SIZE_BYTES = 4096;

    static final String PDF = "application/pdf";
    static final String JPEG = "image/jpeg";
    static final String PNG = "image/png";
    static final String DOC = "application/msword";
    static final String DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** The allowlist of {@code application.yml}, which is what production runs with. */
    static final String ALLOWED_TYPES = String.join(",", PDF, JPEG, PNG, DOC, DOCX);

    final Path storageRoot;

    final Map<UUID, Attachment> attachmentsById = new LinkedHashMap<>();
    final Map<UUID, User> usersById = new LinkedHashMap<>();
    final Map<UUID, WorkflowInstance> instancesById = new LinkedHashMap<>();
    final Map<UUID, Task> tasksById = new LinkedHashMap<>();
    final List<AuditLog> auditEntries = new ArrayList<>();

    final AttachmentRepository attachmentRepository = mock(AttachmentRepository.class);
    final UserRepository userRepository = mock(UserRepository.class);
    final WorkflowInstanceRepository instanceRepository = mock(WorkflowInstanceRepository.class);
    final TaskRepository taskRepository = mock(TaskRepository.class);
    final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);

    final AttachmentStorage storage;
    final AttachmentTypeGate typeGate;
    final InstanceParticipants participants;
    final AttachmentService attachmentService;

    InMemoryAttachmentFixture() {
        this(MAX_SIZE_BYTES, ALLOWED_TYPES);
    }

    InMemoryAttachmentFixture(long maxSizeBytes, String allowedTypes) {
        try {
            storageRoot = Files.createTempDirectory("flowforge-attachments");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }

        when(attachmentRepository.save(any(Attachment.class))).thenAnswer(call -> {
            Attachment attachment = call.getArgument(0);
            if (attachment.getId() == null) {
                attachment.setId(UUID.randomUUID());
                attachment.setCreatedAt(Instant.now());
            }
            attachmentsById.put(attachment.getId(), attachment);
            return attachment;
        });
        when(attachmentRepository.findByInstance_IdOrderByCreatedAtAsc(any(UUID.class)))
                .thenAnswer(call -> attachmentsById.values().stream()
                        .filter(attachment -> call.<UUID>getArgument(0).equals(attachment.instanceId()))
                        .sorted(Comparator.comparing(Attachment::getCreatedAt))
                        .toList());

        when(userRepository.findById(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(usersById.get(call.<UUID>getArgument(0))));

        when(instanceRepository.findById(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(instancesById.get(call.<UUID>getArgument(0))));

        when(taskRepository.findByInstance_IdOrderByCreatedAtAsc(any(UUID.class)))
                .thenAnswer(call -> tasksById.values().stream()
                        .filter(task -> call.<UUID>getArgument(0).equals(task.instanceId()))
                        .toList());

        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(call -> {
            AuditLog entry = call.getArgument(0);
            if (entry.getId() == null) {
                entry.setId(UUID.randomUUID());
            }
            auditEntries.add(entry);
            return entry;
        });

        storage = new AttachmentStorage(storageRoot.toString(), maxSizeBytes);
        typeGate = new AttachmentTypeGate(allowedTypes);
        participants = new InstanceParticipants(instanceRepository, taskRepository);
        attachmentService = new AttachmentService(
                attachmentRepository,
                userRepository,
                participants,
                storage,
                typeGate,
                new AuditLogService(auditLogRepository));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    User user(String name, String roleName) {
        User created = User.builder()
                .id(UUID.randomUUID())
                .name(name)
                .email(name.replace(' ', '.').toLowerCase() + "+" + UUID.randomUUID() + "@example.com")
                .passwordHash("hash")
                .role(Role.builder().id(UUID.randomUUID()).name(roleName)
                        .permissions(new HashMap<>()).build())
                .isActive(true)
                .createdAt(Instant.now())
                .build();
        usersById.put(created.getId(), created);
        return created;
    }

    WorkflowInstance instance(User initiator) {
        WorkflowInstance created = WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .initiatedBy(initiator)
                .build();
        instancesById.put(created.getId(), created);
        return created;
    }

    /** A task on an instance, which is what makes its assignee a participant. */
    Task task(WorkflowInstance instance, User assignee, TaskStatus status) {
        Task created = Task.builder()
                .id(UUID.randomUUID())
                .instance(instance)
                .node(WorkflowNode.builder()
                        .id(UUID.randomUUID())
                        .type(NodeType.APPROVAL)
                        .configJson(new LinkedHashMap<>(Map.of("label", "review")))
                        .build())
                .assignedTo(assignee)
                .status(status)
                .createdAt(Instant.now())
                .build();
        tasksById.put(created.getId(), created);
        return created;
    }

    // ── upload bodies ────────────────────────────────────────────────────────────────────────────

    /** The leading bytes that make a body look like the given type to the sniffer. */
    static byte[] signatureOf(String contentType) {
        return switch (contentType) {
            case PDF -> new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '7'};
            case JPEG -> new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
            case PNG -> new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
            case DOC -> new byte[]{(byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
                    (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1};
            case DOCX -> new byte[]{'P', 'K', 0x03, 0x04};
            default -> new byte[]{'n', 'o', 'p', 'e'};
        };
    }

    /** A body of exactly {@code size} bytes that carries the signature of {@code contentType}. */
    static byte[] bodyOf(String contentType, int size) {
        byte[] signature = signatureOf(contentType);
        byte[] body = new byte[Math.max(size, signature.length)];
        System.arraycopy(signature, 0, body, 0, signature.length);
        for (int i = signature.length; i < body.length; i++) {
            body[i] = (byte) ('a' + (i % 26));
        }
        return body;
    }

    /** An upload part whose bytes match what it declares. */
    static MultipartFile upload(String fileName, String declaredType, int size) {
        return new MockMultipartFile("file", fileName, declaredType, bodyOf(declaredType, size));
    }

    /** An upload part whose bytes are {@code actualType} while it declares {@code declaredType}. */
    static MultipartFile spoofedUpload(String fileName, String declaredType, String actualType) {
        return new MockMultipartFile("file", fileName, declaredType, bodyOf(actualType, 512));
    }

    /**
     * An upload that lies about its length: {@code getSize()} reports a value under the limit while the
     * stream delivers {@code actualSize} bytes. The only defence against this is counting while writing.
     */
    static MultipartFile understatedUpload(String declaredType, long claimedSize, int actualSize) {
        byte[] body = bodyOf(declaredType, actualSize);
        return new MockMultipartFile("file", "understated.pdf", declaredType, body) {
            @Override
            public long getSize() {
                return claimedSize;
            }
        };
    }

    // ── observations ─────────────────────────────────────────────────────────────────────────────

    /** Every regular file under the storage root — what actually got written. */
    List<Path> storedFiles() {
        try (var walk = Files.walk(storageRoot)) {
            return walk.filter(Files::isRegularFile).toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    List<AuditLog> auditEntriesWithAction(String action) {
        return auditEntries.stream().filter(entry -> action.equals(entry.getAction())).toList();
    }

    @Override
    public void close() {
        try (var walk = Files.walk(storageRoot)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover temp directory is not worth failing a test over.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }
}
