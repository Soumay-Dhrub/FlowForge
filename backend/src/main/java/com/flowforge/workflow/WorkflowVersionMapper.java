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

@Mapper(config = MapperConfig.class)
public interface WorkflowVersionMapper {

    @Mapping(source = "workflow.id", target = "workflowId")
    @Mapping(source = "publishedBy.id", target = "publishedById")
    @Mapping(source = "publishedBy.name", target = "publishedByName")
    WorkflowVersionResponse toResponse(WorkflowVersion version);

    List<WorkflowVersionResponse> toResponseList(List<WorkflowVersion> versions);

    @Mapping(source = "version.id", target = "versionId")
    WorkflowNodeResponse toNodeResponse(WorkflowNode node);

    @Mapping(source = "version.id", target = "versionId")
    @Mapping(source = "sourceNode.id", target = "sourceNodeId")
    @Mapping(source = "targetNode.id", target = "targetNodeId")
    WorkflowEdgeResponse toEdgeResponse(WorkflowEdge edge);

    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    WorkflowNode toNode(WorkflowNodeRequest request);

    List<WorkflowNode> toNodes(List<WorkflowNodeRequest> requests);

    @Mapping(target = "version", ignore = true)
    @Mapping(target = "sourceNode", ignore = true)
    @Mapping(target = "targetNode", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    WorkflowEdge toEdge(WorkflowEdgeRequest request);

    List<WorkflowEdge> toEdges(List<WorkflowEdgeRequest> requests);

    SaveDraftRequest toDraftRequest(PublishRequest request);
}
