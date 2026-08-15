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
     * Whether a node's assignee reference points at somebody who exists, checked at publish time
     * (Requirement 7.5).
     *
     * <p>Presence and syntax are not enough: an approver id that parses but names no account fails every
     * request that reaches the node, exactly as a missing key does. That is knowable from the definition,
     * so it belongs at publish.
     *
     * <p><b>A named user is checked; a named role is not.</b> The asymmetry is deliberate. A user id is a
     * hard reference to one account and a typo in it is simply wrong. A role, though, is a description of
     * whoever holds it, and a workflow legitimately gets published before the team is staffed — refusing
     * that would make the platform demand people exist before their process may be defined. An empty role
     * is still caught at execution, where it is genuinely unresolvable.
     *
     * <p>Nor does a clean result here guarantee execution succeeds: the account may be deactivated after
     * publishing, and a frozen version cannot be re-validated. This narrows the window rather than
     * closing it, which is the most publish-time validation can honestly do about a mutable world.
     *
     * @param node      the node to check
     * @param userIdKey config key holding a specific user id
     * @return the violation, or empty when the key is absent or names an assignable user
     */
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

    /**
     * Whether every user id a node addresses points at somebody who exists (Requirement 7.5).
     *
     * <p>The audience counterpart of {@link #validateAssigneeReference}, with the same reasoning: ids are
     * checked, roles are not.
     *
     * @param node       the node to check
     * @param userIdsKey config key holding user ids
     * @return one violation per unresolvable id, empty when all of them resolve
     */
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
