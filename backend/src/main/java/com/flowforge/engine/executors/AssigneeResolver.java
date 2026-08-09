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

/**
 * Turns "who" in a node's config into actual users.
 *
 * <p>A designer names either a person or a role, and the engine needs a row in {@code users} either
 * way — {@code tasks.assigned_to} is {@code NOT NULL} and a notification needs a recipient. Both the
 * Task node (Requirement 9.2) and the Approval node of task 18 ask the same question, so it is asked
 * in one place.
 *
 * <h2>Resolving a role</h2>
 * <p>A role is resolved to its active members, oldest account first. Where a single assignee is
 * needed the first member is taken: the order is total and stable, so the same role resolves to the
 * same person every time rather than to whoever the query planner returned first. That is a
 * deliberate simplification — proper group tasks (a queue several people can claim from) would need a
 * schema that does not force one assignee, and load-aware routing is not something the requirements
 * ask for. Delegation (task 25) and escalation (task 20) then move the task on from that starting
 * assignee.
 *
 * <h2>Failing loudly</h2>
 * <p>Anything unresolvable is a definition defect and throws: a node naming nobody, a user id that
 * does not exist, a role with no active members. The alternative — writing a task with no owner, or
 * quietly picking someone — parks an instance forever with nothing to show why (Requirement 12.1
 * would simply never list it). Since the graph was authored and published before any instance reached
 * the node, no caller can fix it, so these are 500s that name the node and the offending value.
 * Inactive users are excluded for the same reason a deactivated account cannot log in
 * (Requirement 4.2): a task they can never open is not an assignment.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssigneeResolver {

    private final UserRepository userRepository;

    /**
     * The single user a node assigns work to.
     *
     * <p>A specific user takes precedence over a role, so a node carrying both is unambiguous rather
     * than order-dependent.
     *
     * @param node       the node being executed, used for the error message
     * @param userIdKey  config key holding a specific user id, e.g. {@code assigneeUserId}
     * @param roleKey    config key holding a role name, e.g. {@code assigneeRole}
     * @return the resolved assignee
     * @throws AppException 500 when the config names nobody, or names somebody who cannot be assigned
     */
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

    /**
     * Every user a node addresses — a list of user ids, a list of role names, or both, de-duplicated
     * and in the order authored.
     *
     * <p>Unlike {@link #resolveAssignee} a role contributes all of its active members: a notification
     * naming a role means the role, not one representative of it.
     *
     * @param node        the node being executed, used for the error message
     * @param userIdsKey  config key holding user ids
     * @param rolesKey    config key holding role names
     * @return the resolved audience, empty when neither key is configured
     * @throws AppException 500 when a named user or role resolves to nobody assignable
     */
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
