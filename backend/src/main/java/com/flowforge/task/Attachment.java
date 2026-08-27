package com.flowforge.task;

import com.flowforge.engine.WorkflowInstance;
import com.flowforge.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "attachments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The request this file belongs to. Deleted with it — {@code ON DELETE CASCADE} in the schema. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instance_id", nullable = false, updatable = false)
    private WorkflowInstance instance;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false, updatable = false)
    private User uploadedBy;

    /** The sanitised original name, for display and download. Never used as a path. */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** The MIME type the upload was accepted as. */
    @Column(name = "content_type", nullable = false, length = 127)
    private String contentType;

    /** Size in bytes, counted while writing rather than taken from the request. */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** Where the bytes are, relative to the configured storage root. */
    @Column(name = "storage_path", nullable = false, length = 512)
    private String storagePath;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * @return the id of the instance this file belongs to, or {@code null} if unbound
     */
    @Transient
    public UUID instanceId() {
        return instance == null ? null : instance.getId();
    }

    /**
     * @return the id of the uploader, or {@code null} if unbound
     */
    @Transient
    public UUID uploaderId() {
        return uploadedBy == null ? null : uploadedBy.getId();
    }
}
