package com.jake.realtimeapi.leaderboards.application;

import com.jake.realtimeapi.events.domain.repository.RankingWarmthRepository;
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
    private final RankingWarmthRepository rankingWarmthRepository;

    public LeaderboardApplicationService(
            LeaderboardRepository leaderboardRepository,
            ProjectRepository projectRepository,
            RankingWarmthRepository rankingWarmthRepository
    ) {
        this.leaderboardRepository = leaderboardRepository;
        this.projectRepository = projectRepository;
        this.rankingWarmthRepository = rankingWarmthRepository;
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
        Leaderboard leaderboard = leaderboardRepository.save(Leaderboard.newLeaderboard(command.projectId(), command.name()));
        // 새 리더보드는 잃을 데이터가 없으므로 처음부터 warm이다. 없으면 첫 쓰기가 cold로 거부된다.
        rankingWarmthRepository.markWarm(leaderboard.id());
        return leaderboard;
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
