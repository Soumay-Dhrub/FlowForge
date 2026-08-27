package com.flowforge.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    Optional<User> findByIdAndIsActiveTrue(UUID id);

    List<User> findByRole_NameIgnoreCaseAndIsActiveTrueOrderByCreatedAtAscIdAsc(String roleName);
}
