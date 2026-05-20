package com.jake.realtimeapi.leaderboards.api;

import com.jake.realtimeapi.auth.api.CurrentAdminUser;
import com.jake.realtimeapi.auth.domain.model.AuthenticatedAdminUser;
import com.jake.realtimeapi.leaderboards.api.dto.CreateLeaderboardRequest;
import com.jake.realtimeapi.leaderboards.api.dto.LeaderboardResponse;
import com.jake.realtimeapi.leaderboards.api.dto.ListLeaderboardResponse;
import com.jake.realtimeapi.leaderboards.application.command.CreateLeaderboardCommand;
import com.jake.realtimeapi.leaderboards.application.usecase.CreateLeaderboardUseCase;
import com.jake.realtimeapi.leaderboards.application.usecase.GetLeaderboardUseCase;
import com.jake.realtimeapi.leaderboards.application.usecase.ListLeaderboardUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/leaderboards")
public class LeaderboardController {

    private final CreateLeaderboardUseCase createLeaderboardUseCase;
    private final GetLeaderboardUseCase getLeaderboardUseCase;
    private final ListLeaderboardUseCase listLeaderboardUseCase;

    public LeaderboardController(
            CreateLeaderboardUseCase createLeaderboardUseCase,
            GetLeaderboardUseCase getLeaderboardUseCase,
            ListLeaderboardUseCase listLeaderboardResponse
    ) {
        this.createLeaderboardUseCase = createLeaderboardUseCase;
        this.getLeaderboardUseCase = getLeaderboardUseCase;
        this.listLeaderboardUseCase = listLeaderboardResponse;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LeaderboardResponse create(
            @CurrentAdminUser AuthenticatedAdminUser currentAdminUser,
            @Valid @RequestBody CreateLeaderboardRequest request
    ) {
        return LeaderboardApiMapper.toResponse(
                createLeaderboardUseCase.create(
                        new CreateLeaderboardCommand(request.projectId(), request.name(), currentAdminUser.userId())
                )
        );
    }

    @GetMapping("/{leaderboardId}")
    public LeaderboardResponse get(@PathVariable UUID leaderboardId) {
        return LeaderboardApiMapper.toResponse(getLeaderboardUseCase.getById(leaderboardId));
    }

    @GetMapping
    public ListLeaderboardResponse get(
            @RequestParam UUID projectId,
            @RequestParam(required = false) String name
    ) {
        if(name != null) {
            return LeaderboardApiMapper.toResponse(List.of(getLeaderboardUseCase.getByName(projectId, name)));
        }
        return LeaderboardApiMapper.toResponse(listLeaderboardUseCase.getByProjectId(projectId));
    }
}
