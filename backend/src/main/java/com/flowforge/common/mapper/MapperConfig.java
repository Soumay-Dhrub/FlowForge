package com.flowforge.common.mapper;

import org.mapstruct.ReportingPolicy;

/**
 * Base configuration for all MapStruct mappers.
 * This configuration is shared across all mappers in the application.
 *
 * <p>Note: {@code org.mapstruct.MapperConfig} is referenced by its fully qualified name because
 * a single-type import cannot share a simple name with a type declared in the same file.
 */
@org.mapstruct.MapperConfig(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface MapperConfig {
}
