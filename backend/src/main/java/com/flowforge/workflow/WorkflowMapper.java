package com.flowforge.workflow;

import com.flowforge.common.mapper.MapperConfig;
import com.flowforge.workflow.dto.WorkflowResponse;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

@Mapper(
        config = MapperConfig.class,
        uses = WorkflowVersionMapper.class,
        injectionStrategy = InjectionStrategy.CONSTRUCTOR
)
public interface WorkflowMapper {

    @Named("summary")
    @Mapping(source = "createdBy.id", target = "createdById")
    @Mapping(source = "createdBy.name", target = "createdByName")
    @Mapping(target = "versions", ignore = true)
    WorkflowResponse toResponse(Workflow workflow);

    @Named("detail")
    @Mapping(source = "createdBy.id", target = "createdById")
    @Mapping(source = "createdBy.name", target = "createdByName")
    WorkflowResponse toDetailResponse(Workflow workflow);

    @IterableMapping(qualifiedByName = "summary")
    List<WorkflowResponse> toResponseList(List<Workflow> workflows);
}
