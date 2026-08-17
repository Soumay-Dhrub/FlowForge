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
 * <h2>Threading, one level deep</h2>
 * <p>A comment with no {@link #parent} is top-level; one with a parent is a reply to it
 * (Requirement 15.1). Replies to replies are refused: a decision thread is a conversation, and arbitrary
 * nesting turns it into something a reviewer has to navigate rather than read. One level expresses
 * "answering that point" — which is the whole of what the requirement asks for — while keeping the read
 * model a list of parents each holding its replies, in the order both were written (Requirement 15.2).
 *
 * <p>A reply must belong to the same request as its parent. {@code CommentService} enforces that, because
 * a foreign key cannot: {@code parent_comment_id} only says the parent exists, not that it is part of this
 * conversation, and without the check a reply could quote a comment from a request its author cannot even
 * read.
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

    /**
     * The comment this one replies to, or {@code null} for a top-level comment.
     *
     * <p>Not updatable: moving a reply under a different parent would rewrite who appeared to be
     * answering whom, which is precisely the history this table exists to preserve.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_comment_id", updatable = false)
    private Comment parent;

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

    /**
     * @return the id of the comment this replies to, or {@code null} when top-level
     */
    @Transient
    public UUID parentId() {
        return parent == null ? null : parent.getId();
    }

    /**
     * @return {@code true} when this comment is a reply rather than a new point
     */
    @Transient
    public boolean isReply() {
        return parent != null;
    }
}
