package com.jake.realtimeapi.users.application;

import com.jake.realtimeapi.users.application.command.CreateUserCommand;
import com.jake.realtimeapi.users.application.usecase.CreateUserUseCase;
import com.jake.realtimeapi.users.application.usecase.GetUserUseCase;
import com.jake.realtimeapi.users.application.usecase.ListUsersUseCase;
import com.jake.realtimeapi.users.application.usecase.UserSlice;
import com.jake.realtimeapi.users.domain.exception.UserAlreadyExistsException;
import com.jake.realtimeapi.users.domain.exception.UserNotFoundException;
import com.jake.realtimeapi.users.domain.model.User;
import com.jake.realtimeapi.users.domain.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserApplicationService implements CreateUserUseCase, GetUserUseCase, ListUsersUseCase {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final UserRepository userRepository;

    public UserApplicationService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public User create(CreateUserCommand command) {
        if (userRepository.existsByExternalId(command.externalId())) {
            throw new UserAlreadyExistsException(command.externalId());
        }
        return userRepository.save(User.newUser(command.externalId()));
    }

    @Override
    public User getById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    @Override
    public User getByExternalId(String externalId) {
        return userRepository.findByExternalId(externalId)
                .orElseThrow(() -> new UserNotFoundException(externalId));
    }

    @Override
    public UserSlice list(int offset, int limit) {
        int normalizedOffset = Math.max(0, offset);
        int normalizedLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return new UserSlice(userRepository.findPage(normalizedOffset, normalizedLimit), normalizedOffset, normalizedLimit);
    }
}
