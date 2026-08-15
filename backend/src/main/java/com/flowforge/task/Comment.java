package com.flowforge.task;

import com.flowforge.engine.WorkflowInstance;
import com.flowforge.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A remark posted on a request (Requirements 15.1, 15.2, 15.3).
 *
 * <h2>Flat, not nested</h2>
 * <p>Requirement 15 calls these "threaded comments", but {@code comments} has no {@code parent_comment_id}
 * and this task adds no migration for one, so a thread here means <em>one</em> conversation per request,
 * read in the order it was written — the shape of a chat log rather than a reply tree. That is a genuine
 * discrepancy between the requirement's wording and the schema, and it is flagged rather than resolved by
 * inventing a column: adding one changes the read model (a tree, ordered per branch), the API (a parent id
 * on the request DTO) and the access rule (a reply to a comment on another instance), and that is a design
 * decision to take deliberately, not a side effect of this task.
 *
 * <p>No {@code updated_at} column, and none wanted. A comment is a statement someone made at a point in a
 * decision process; silently editable history would undermine the audit trail the platform exists to keep
 * (Requirement 19.1). Correcting yourself means posting again.
 */
@Entity
@Table(name = "comments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The request being discussed. Deleted with it — {@code ON DELETE CASCADE} in the schema. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instance_id", nullable = false, updatable = false)
    private WorkflowInstance instance;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false, updatable = false)
    private User author;

    /** What was said. {@code TEXT}, so length is a DTO-level concern rather than a column limit. */
    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * @return the id of the request this comment belongs to, or {@code null} if unbound
     */
    @Transient
    public UUID instanceId() {
        return instance == null ? null : instance.getId();
    }

    /**
     * @return the id of the author, or {@code null} if unbound
     */
    @Transient
    public UUID authorId() {
        return author == null ? null : author.getId();
    }
}
