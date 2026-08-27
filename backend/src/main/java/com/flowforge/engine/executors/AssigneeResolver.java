package com.flowforge.engine.executors;

import com.flowforge.common.exception.AppException;
import com.flowforge.user.User;
import com.flowforge.user.UserRepository;
import com.flowforge.workflow.WorkflowNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class AssigneeResolver {

    private final UserRepository userRepository;

    public User resolveAssignee(WorkflowNode node, String userIdKey, String roleKey) {
        Optional<UUID> userId = NodeConfig.uuid(node, userIdKey);
        if (userId.isPresent()) {
            return requireActiveUser(node, userIdKey, userId.get());
        }

        Optional<String> roleName = NodeConfig.string(node, roleKey);
        if (roleName.isPresent()) {
            List<User> members = activeMembersOf(roleName.get());
            if (members.isEmpty()) {
                throw NodeConfig.defect(node, roleKey, roleName.get(),
                        "resolves to no active user; the task cannot be assigned");
            }
            User assignee = members.getFirst();
            log.debug("Node {} assignment role '{}' resolved to user {}",
                    node.getId(), roleName.get(), assignee.getId());
            return assignee;
        }

        throw NodeConfig.defect(node, "configures no assignee: set '%s' to a user id or '%s' to a role name"
                .formatted(userIdKey, roleKey));
    }

    public Optional<String> validateAssigneeReference(WorkflowNode node, String userIdKey) {
        Optional<UUID> userId;
        try {
            userId = NodeConfig.uuid(node, userIdKey);
        } catch (AppException malformed) {
            return Optional.of(malformed.getMessage());
        }

        return userId
                .filter(id -> userRepository.findByIdAndIsActiveTrue(id).isEmpty())
                .map(id -> NodeConfig.defect(node, userIdKey, id.toString(),
                        "does not name an active user").getMessage());
    }

    public List<String> validateAudienceReferences(WorkflowNode node, String userIdsKey) {
        List<String> raw;
        try {
            raw = NodeConfig.strings(node, userIdsKey);
        } catch (AppException malformed) {
            return List.of(malformed.getMessage());
        }

        List<String> violations = new ArrayList<>();
        for (String value : raw) {
            UUID id;
            try {
                id = UUID.fromString(value);
            } catch (IllegalArgumentException notAnId) {
                violations.add(NodeConfig.defect(node, userIdsKey, value,
                        "is not a valid identifier").getMessage());
                continue;
            }
            if (userRepository.findByIdAndIsActiveTrue(id).isEmpty()) {
                violations.add(NodeConfig.defect(node, userIdsKey, value,
                        "does not name an active user").getMessage());
            }
        }
        return List.copyOf(violations);
    }

    public List<User> resolveAudience(WorkflowNode node, String userIdsKey, String rolesKey) {
        Map<UUID, User> audience = new LinkedHashMap<>();

        for (String rawUserId : NodeConfig.strings(node, userIdsKey)) {
            UUID userId;
            try {
                userId = UUID.fromString(rawUserId);
            } catch (IllegalArgumentException malformed) {
                throw NodeConfig.defect(node, userIdsKey, rawUserId, "is not a valid identifier");
            }
            User user = requireActiveUser(node, userIdsKey, userId);
            audience.putIfAbsent(user.getId(), user);
        }

        for (String roleName : NodeConfig.strings(node, rolesKey)) {
            List<User> members = activeMembersOf(roleName);
            if (members.isEmpty()) {
                throw NodeConfig.defect(node, rolesKey, roleName, "resolves to no active user");
            }
            members.forEach(member -> audience.putIfAbsent(member.getId(), member));
        }

        return List.copyOf(new ArrayList<>(audience.values()));
    }

    private User requireActiveUser(WorkflowNode node, String key, UUID userId) {
        return userRepository.findByIdAndIsActiveTrue(userId)
                .orElseThrow(() -> NodeConfig.defect(node, key, userId.toString(),
                        "does not identify an active user"));
    }

    private List<User> activeMembersOf(String roleName) {
        return userRepository.findByRole_NameIgnoreCaseAndIsActiveTrueOrderByCreatedAtAscIdAsc(roleName);
    }
}
