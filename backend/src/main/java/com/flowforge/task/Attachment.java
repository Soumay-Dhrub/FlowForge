package com.flowforge.task;

import com.flowforge.engine.WorkflowInstance;
import com.flowforge.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A file that travels with a request (Requirements 14.1, 14.2, 14.3).
 *
 * <p>Metadata only. The bytes live on the configured storage path and this row points at them through
 * {@link #storagePath}, which is <b>relative to the storage root</b> — never absolute. Storing it
 * relative means the root can move (a different volume mount, a different container) without a data
 * migration, and it keeps a database dump from advertising the host's filesystem layout.
 *
 * <p>{@link #fileName} is the name the uploader's browser sent, sanitised down to a bare file name for
 * display and download only. It is deliberately <em>not</em> what the file is called on disk: the name
 * on disk is generated (see {@code AttachmentStorage}), so a client-supplied name cannot influence
 * where bytes land or overwrite an existing file.
 *
 * <p>{@link #contentType} is the type the upload was accepted as — the declared type, confirmed
 * against the bytes' signature — so a later download can serve it back without re-sniffing.
 *
 * <p>There is no {@code updated_at}: an attachment is written once. Replacing a document means
 * uploading a new one, which keeps the request's paper trail intact (Requirement 19.1).
 */
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
