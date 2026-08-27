package com.flowforge.task;

import com.flowforge.engine.WorkflowInstance;
import com.flowforge.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

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
