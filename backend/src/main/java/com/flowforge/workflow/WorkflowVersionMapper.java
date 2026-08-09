package com.flowforge.workflow;

import com.flowforge.common.mapper.MapperConfig;
import com.flowforge.workflow.dto.PublishRequest;
import com.flowforge.workflow.dto.SaveDraftRequest;
import com.flowforge.workflow.dto.WorkflowEdgeRequest;
import com.flowforge.workflow.dto.WorkflowEdgeResponse;
import com.flowforge.workflow.dto.WorkflowNodeRequest;
import com.flowforge.workflow.dto.WorkflowNodeResponse;
import com.flowforge.workflow.dto.WorkflowVersionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * MapStruct mapper for WorkflowVersion, WorkflowNode and WorkflowEdge transformations.
 *
 * <p>Associations that require a database lookup (the owning version, and an edge's source and
 * target nodes) are deliberately left unmapped: the service layer owns those lookups so a request
 * payload can never smuggle in a detached entity reference.
 */
@Mapper(config = MapperConfig.class)
public interface WorkflowVersionMapper {

    /**
     * Convert a WorkflowVersion entity to its response DTO, including the graph.
     *
     * @param version the WorkflowVersion entity
     * @return WorkflowVersionResponse DTO
     */
    @Mapping(source = "workflow.id", target = "workflowId")
    @Mapping(source = "publishedBy.id", target = "publishedById")
    @Mapping(source = "publishedBy.name", target = "publishedByName")
    WorkflowVersionResponse toResponse(WorkflowVersion version);

    /**
     * Convert a list of WorkflowVersion entities to response DTOs.
     *
     * @param versions the WorkflowVersion entities
     * @return the response DTOs in the same order
     */
    List<WorkflowVersionResponse> toResponseList(List<WorkflowVersion> versions);

    /**
     * Convert a WorkflowNode entity to its response DTO.
     *
     * @param node the WorkflowNode entity
     * @return WorkflowNodeResponse DTO
     */
    @Mapping(source = "version.id", target = "versionId")
    WorkflowNodeResponse toNodeResponse(WorkflowNode node);

    /**
     * Convert a WorkflowEdge entity to its response DTO.
     *
     * @param edge the WorkflowEdge entity
     * @return WorkflowEdgeResponse DTO
     */
    @Mapping(source = "version.id", target = "versionId")
    @Mapping(source = "sourceNode.id", target = "sourceNodeId")
    @Mapping(source = "targetNode.id", target = "targetNodeId")
    WorkflowEdgeResponse toEdgeResponse(WorkflowEdge edge);

    /**
     * Convert a node request to a WorkflowNode entity. The owning version is set by the service.
     *
     * @param request the node request
     * @return WorkflowNode entity (version not yet attached)
     */
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    WorkflowNode toNode(WorkflowNodeRequest request);

    /**
     * Convert node requests to WorkflowNode entities, preserving payload order.
     *
     * @param requests the node requests
     * @return WorkflowNode entities
     */
    List<WorkflowNode> toNodes(List<WorkflowNodeRequest> requests);

    /**
     * Convert an edge request to a WorkflowEdge entity. The owning version and the source and target
     * nodes are resolved and set by the service.
     *
     * @param request the edge request
     * @return WorkflowEdge entity (associations not yet attached)
     */
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "sourceNode", ignore = true)
    @Mapping(target = "targetNode", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    WorkflowEdge toEdge(WorkflowEdgeRequest request);

    /**
     * Convert edge requests to WorkflowEdge entities, preserving payload order.
     *
     * @param requests the edge requests
     * @return WorkflowEdge entities
     */
    List<WorkflowEdge> toEdges(List<WorkflowEdgeRequest> requests);

    /**
     * Reduce a publish request to a draft-save payload so publishing can reuse the draft-save path.
     *
     * @param request the publish request
     * @return an equivalent SaveDraftRequest
     */
    SaveDraftRequest toDraftRequest(PublishRequest request);
}
