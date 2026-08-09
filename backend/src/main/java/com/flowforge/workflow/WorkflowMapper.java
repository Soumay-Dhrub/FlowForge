package com.flowforge.workflow;

import com.flowforge.common.mapper.MapperConfig;
import com.flowforge.workflow.dto.WorkflowResponse;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

/**
 * MapStruct mapper for Workflow entity transformations.
 *
 * <p>Delegates version mapping to {@link WorkflowVersionMapper}; constructor injection keeps the
 * generated implementation usable outside a Spring context.
 */
@Mapper(
        config = MapperConfig.class,
        uses = WorkflowVersionMapper.class,
        injectionStrategy = InjectionStrategy.CONSTRUCTOR
)
public interface WorkflowMapper {

    /**
     * Convert a Workflow entity to its response DTO without the version history.
     *
     * @param workflow the Workflow entity
     * @return WorkflowResponse DTO with {@code versions} left null
     */
    @Named("summary")
    @Mapping(source = "createdBy.id", target = "createdById")
    @Mapping(source = "createdBy.name", target = "createdByName")
    @Mapping(target = "versions", ignore = true)
    WorkflowResponse toResponse(Workflow workflow);

    /**
     * Convert a Workflow entity to its response DTO including the ordered version history
     * (Requirement 8.3).
     *
     * @param workflow the Workflow entity
     * @return WorkflowResponse DTO with {@code versions} populated
     */
    @Named("detail")
    @Mapping(source = "createdBy.id", target = "createdById")
    @Mapping(source = "createdBy.name", target = "createdByName")
    WorkflowResponse toDetailResponse(Workflow workflow);

    /**
     * Convert a list of Workflow entities to response DTOs without their version histories.
     *
     * @param workflows the Workflow entities
     * @return the response DTOs in the same order
     */
    @IterableMapping(qualifiedByName = "summary")
    List<WorkflowResponse> toResponseList(List<Workflow> workflows);
}
