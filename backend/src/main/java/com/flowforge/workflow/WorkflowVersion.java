package com.flowforge.workflow;

import com.flowforge.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "workflow_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_id", nullable = false)
    private Workflow workflow;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    /** Frozen snapshot of the graph, shaped as {@code {"nodes":[...],"edges":[...]}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "graph_json", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> graphJson = emptyGraph();

    @Column(name = "is_published", nullable = false)
    @Builder.Default
    private Boolean isPublished = false;

    @Column(name = "is_current", nullable = false)
    @Builder.Default
    private Boolean isCurrent = false;

    @Column(name = "published_at")
    private Instant publishedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "published_by")
    private User publishedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "version", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<WorkflowNode> nodes = new ArrayList<>();

    @OneToMany(mappedBy = "version", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<WorkflowEdge> edges = new ArrayList<>();

    /**
     * @return an empty graph payload matching the column default in the schema
     */
    public static Map<String, Object> emptyGraph() {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", new ArrayList<>());
        graph.put("edges", new ArrayList<>());
        return graph;
    }

    /**
     * Attach a node to this version, keeping both sides of the association in sync.
     *
     * @param node the node to add
     */
    public void addNode(WorkflowNode node) {
        nodes.add(node);
        node.setVersion(this);
    }

    /**
     * Attach an edge to this version, keeping both sides of the association in sync.
     *
     * @param edge the edge to add
     */
    public void addEdge(WorkflowEdge edge) {
        edges.add(edge);
        edge.setVersion(this);
    }

    /**
     * @return {@code true} when the version is still an editable draft
     */
    @Transient
    public boolean isDraft() {
        return !Boolean.TRUE.equals(isPublished);
    }
}
