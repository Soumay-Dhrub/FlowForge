package com.flowforge.user;

import com.flowforge.common.mapper.MapperConfig;
import com.flowforge.user.dto.CreateUserRequest;
import com.flowforge.user.dto.UpdateUserRequest;
import com.flowforge.user.dto.UserResponse;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

/**
 * MapStruct mapper for User entity transformations.
 */
@Mapper(config = MapperConfig.class)
public interface UserMapper {

    /**
     * Convert User entity to UserResponse DTO.
     *
     * @param user the User entity
     * @return UserResponse DTO
     */
    @Mapping(source = "role.id", target = "roleId")
    @Mapping(source = "role.name", target = "roleName")
    @Mapping(source = "department.id", target = "departmentId")
    @Mapping(source = "department.name", target = "departmentName")
    UserResponse toResponse(User user);

    /**
     * Convert CreateUserRequest DTO to User entity.
     * Note: password hashing and setting role/department entities should be handled in service layer.
     *
     * @param request the CreateUserRequest DTO
     * @return User entity (partially populated)
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "role", ignore = true)
    @Mapping(target = "department", ignore = true)
    @Mapping(target = "isActive", constant = "true")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    User toEntity(CreateUserRequest request);

    /**
     * Apply the non-null fields of an UpdateUserRequest onto an existing User entity.
     * Role and department resolution stays in the service layer, which owns the lookups.
     *
     * @param request the UpdateUserRequest DTO
     * @param user    the target User entity to mutate
     */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "email", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "role", ignore = true)
    @Mapping(target = "department", ignore = true)
    @Mapping(target = "isActive", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntity(UpdateUserRequest request, @MappingTarget User user);
}
