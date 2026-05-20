package com.jake.realtimeapi.snapshots.api;

import com.jake.realtimeapi.snapshots.api.dto.SnapshotEntriesResponse;
import com.jake.realtimeapi.snapshots.application.model.SnapshotEntriesResult;
import com.jake.realtimeapi.snapshots.application.query.GetSnapshotEntriesQuery;
import com.jake.realtimeapi.snapshots.application.usecase.GetSnapshotEntriesUseCase;
import com.jake.realtimeapi.support.userid.UserIdCodec;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/snapshots")
public class InternalSnapshotEntriesController {

    private final GetSnapshotEntriesUseCase getSnapshotEntriesUseCase;

    public InternalSnapshotEntriesController(GetSnapshotEntriesUseCase getSnapshotEntriesUseCase) {
        this.getSnapshotEntriesUseCase = getSnapshotEntriesUseCase;
    }

    @GetMapping("/{leaderboardId}/entries")
    public SnapshotEntriesResponse getSnapshotEntries(
            @PathVariable UUID leaderboardId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant snapshotAt,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit
    ) {
        SnapshotEntriesResult result = getSnapshotEntriesUseCase.getSnapshotEntries(
                new GetSnapshotEntriesQuery(leaderboardId, snapshotAt, offset, limit)
        );

        List<SnapshotEntriesResponse.SnapshotEntryItemResponse> items = result.items().stream()
                .map(item -> new SnapshotEntriesResponse.SnapshotEntryItemResponse(
                        item.rank(),
                        UserIdCodec.format(item.userId()),
                        item.score()
                ))
                .toList();

        return new SnapshotEntriesResponse(
                result.leaderboardId(),
                result.snapshotId(),
                result.snapshotAt(),
                result.topN(),
                result.total(),
                items
        );
    }
}
