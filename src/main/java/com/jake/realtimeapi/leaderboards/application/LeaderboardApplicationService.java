package com.jake.realtimeapi.leaderboards.application;

import com.jake.realtimeapi.leaderboards.application.command.CreateLeaderboardCommand;
import com.jake.realtimeapi.leaderboards.application.usecase.CreateLeaderboardUseCase;
import com.jake.realtimeapi.leaderboards.application.usecase.GetLeaderboardUseCase;
import com.jake.realtimeapi.leaderboards.application.usecase.ListLeaderboardUseCase;
import com.jake.realtimeapi.leaderboards.domain.exception.LeaderboardAlreadyExistsException;
import com.jake.realtimeapi.leaderboards.domain.exception.LeaderboardNotFoundException;
import com.jake.realtimeapi.leaderboards.domain.model.Leaderboard;
import com.jake.realtimeapi.leaderboards.domain.repository.LeaderboardRepository;
import com.jake.realtimeapi.projects.domain.exception.ProjectAccessDeniedException;
import com.jake.realtimeapi.projects.domain.exception.ProjectNotFoundException;
import com.jake.realtimeapi.projects.domain.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class LeaderboardApplicationService implements CreateLeaderboardUseCase, GetLeaderboardUseCase, ListLeaderboardUseCase {

    private final LeaderboardRepository leaderboardRepository;
    private final ProjectRepository projectRepository;

    public LeaderboardApplicationService(
            LeaderboardRepository leaderboardRepository,
            ProjectRepository projectRepository
    ) {
        this.leaderboardRepository = leaderboardRepository;
        this.projectRepository = projectRepository;
    }

    @Override
    @Transactional
    public Leaderboard create(CreateLeaderboardCommand command) {
        var project = projectRepository.findById(command.projectId())
                .orElseThrow(() -> new ProjectNotFoundException(command.projectId()));
        if (!command.requesterAdminId().equals(project.adminId())) {
            throw new ProjectAccessDeniedException(command.projectId(), command.requesterAdminId());
        }
        if(leaderboardRepository.existsByName(command.projectId(), command.name())) {
            throw new LeaderboardAlreadyExistsException(command.projectId(), command.name());
        }
        return leaderboardRepository.save(Leaderboard.newLeaderboard(command.projectId(), command.name()));
    }

    @Override
    public Leaderboard getById(UUID id) {
        return leaderboardRepository.findById(id)
                .orElseThrow(() -> new LeaderboardNotFoundException(id));
    }

    @Override
    public Leaderboard getByName(UUID projectId, String name) {
        return leaderboardRepository.findByName(projectId, name)
                .orElseThrow(() -> new LeaderboardNotFoundException(projectId, name));
    }

    @Override
    public List<Leaderboard> getByProjectId(UUID projectId) {
        return leaderboardRepository.findByProjectId(projectId);
    }
}
