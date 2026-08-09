package com.flowforge.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for User entity operations.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Find a user by their email address.
     */
    Optional<User> findByEmail(String email);

    /**
     * Find an active user by their ID.
     */
    Optional<User> findByIdAndIsActiveTrue(UUID id);

    /**
     * Active members of a role, oldest first — how a workflow node that names a role rather than a
     * person is resolved to an actual assignee or recipient (Requirements 11.2, 17.1).
     *
     * <p>Case-insensitive because a node's config carries a role name typed by a designer, while the
     * seeded names are uppercase. The order is total and stable ({@code created_at} then {@code id}),
     * so resolving the same role twice picks the same person rather than whichever row the planner
     * happened to return first.
     */
    List<User> findByRole_NameIgnoreCaseAndIsActiveTrueOrderByCreatedAtAscIdAsc(String roleName);
}
