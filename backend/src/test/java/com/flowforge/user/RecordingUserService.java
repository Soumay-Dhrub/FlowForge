package com.flowforge.user;

import com.flowforge.user.dto.CreateUserRequest;
import com.flowforge.user.dto.UpdateUserRequest;
import com.flowforge.user.dto.UserResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hand-written {@link UserService} double that records the calls it receives.
 *
 * <p>Used by the controller-level property tests, where the subject is the HTTP and authorization
 * behaviour rather than the service logic. It is written by hand rather than mocked because the
 * pinned Mockito/Byte Buddy versions cannot instrument concrete classes on this JDK — mocks of
 * interfaces (the repositories) still work and are used elsewhere.</p>
 */
class RecordingUserService extends UserService {

    static final UserResponse DUMMY = new UserResponse(
            UUID.randomUUID(), "Ada Lovelace", "ada@example.com",
            UUID.randomUUID(), "EMPLOYEE", UUID.randomUUID(), "Engineering",
            true, Instant.now(), Instant.now());

    final List<CreateUserRequest> createdUsers = new ArrayList<>();
    final List<UUID> fetchedUsers = new ArrayList<>();
    final List<UUID> updatedUsers = new ArrayList<>();
    final List<UUID> statusChanges = new ArrayList<>();
    int listCalls;

    RecordingUserService() {
        super(null, null, null, null, null, null, null);
    }

    @Override
    public UserResponse createUser(CreateUserRequest request) {
        createdUsers.add(request);
        return DUMMY;
    }

    @Override
    public List<UserResponse> listUsers() {
        listCalls++;
        return List.of(DUMMY);
    }

    @Override
    public UserResponse getUser(UUID userId) {
        fetchedUsers.add(userId);
        return DUMMY;
    }

    @Override
    public UserResponse updateUser(UUID userId, UpdateUserRequest request) {
        updatedUsers.add(userId);
        return DUMMY;
    }

    @Override
    public UserResponse setAccountStatus(UUID userId, boolean active) {
        statusChanges.add(userId);
        return DUMMY;
    }
}
