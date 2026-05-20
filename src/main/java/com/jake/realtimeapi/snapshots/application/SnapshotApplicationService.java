package com.jake.realtimeapi.snapshots.application;

import com.jake.realtimeapi.snapshots.application.command.CaptureSnapshotCommand;
import com.jake.realtimeapi.snapshots.application.command.RecoverLeaderboardSnapshotCommand;
import com.jake.realtimeapi.snapshots.application.model.CaptureSnapshotResult;
import com.jake.realtimeapi.snapshots.application.model.RecoverLeaderboardSnapshotResult;
import com.jake.realtimeapi.snapshots.application.model.SnapshotEntriesResult;
import com.jake.realtimeapi.snapshots.application.model.SnapshotEntryResult;
import com.jake.realtimeapi.snapshots.application.model.SnapshotStatus;
import com.jake.realtimeapi.snapshots.application.query.GetSnapshotEntriesQuery;
import com.jake.realtimeapi.snapshots.application.usecase.CaptureSnapshotUseCase;
import com.jake.realtimeapi.snapshots.application.usecase.FindTopRanksUseCase;
import com.jake.realtimeapi.snapshots.application.usecase.GetSnapshotEntriesUseCase;
import com.jake.realtimeapi.snapshots.application.usecase.GetSnapshotStatusUseCase;
import com.jake.realtimeapi.snapshots.application.usecase.RecoverLeaderboardSnapshotUseCase;
import com.jake.realtimeapi.snapshots.domain.exception.SnapshotNotFoundException;
import com.jake.realtimeapi.snapshots.domain.model.SnapshotBatches;
import com.jake.realtimeapi.snapshots.domain.model.SnapshotEntries;
import com.jake.realtimeapi.snapshots.domain.model.SnapshotRankingRow;
import com.jake.realtimeapi.snapshots.domain.repository.SnapshotBatchesRepository;
import com.jake.realtimeapi.snapshots.domain.repository.SnapshotEntriesRepository;
import com.jake.realtimeapi.snapshots.domain.repository.SnapshotRankingQueryRepository;
import com.jake.realtimeapi.snapshots.domain.repository.SnapshotRankingRestoreRepository;
import com.jake.realtimeapi.snapshots.domain.repository.command.SnapshotBatchUpsertParam;
import com.jake.realtimeapi.snapshots.domain.repository.command.SnapshotEntryUpsertParam;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class SnapshotApplicationService implements
        FindTopRanksUseCase,
        CaptureSnapshotUseCase,
        GetSnapshotStatusUseCase,
        GetSnapshotEntriesUseCase,
        RecoverLeaderboardSnapshotUseCase {

    private static final int MIN_OFFSET = 0;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 1_000;

    private final SnapshotRankingQueryRepository snapshotRankingQueryRepository;
    private final SnapshotBatchesRepository snapshotBatchesRepository;
    private final SnapshotEntriesRepository snapshotEntriesRepository;
    private final SnapshotRankingRestoreRepository snapshotRankingRestoreRepository;
    private final SnapshotStatusTracker snapshotStatusTracker;

    public SnapshotApplicationService(
            SnapshotRankingQueryRepository snapshotRankingQueryRepository,
            SnapshotBatchesRepository snapshotBatchesRepository,
            SnapshotEntriesRepository snapshotEntriesRepository,
            SnapshotRankingRestoreRepository snapshotRankingRestoreRepository,
            SnapshotStatusTracker snapshotStatusTracker
    ) {
        this.snapshotRankingQueryRepository = snapshotRankingQueryRepository;
        this.snapshotBatchesRepository = snapshotBatchesRepository;
        this.snapshotEntriesRepository = snapshotEntriesRepository;
        this.snapshotRankingRestoreRepository = snapshotRankingRestoreRepository;
        this.snapshotStatusTracker = snapshotStatusTracker;
    }

    @Override
    public List<SnapshotRankingRow> findTopRanks(UUID leaderboardId, int limit) {
        // 조회 전용 유스케이스: Redis Top-N을 그대로 반환한다.
        return snapshotRankingQueryRepository.findTopRanks(leaderboardId, limit);
    }

    @Override
    public SnapshotStatus getStatus() {
        // 상태 조회 유스케이스: worker가 기록한 현재 snapshot 상태를 그대로 반환한다.
        return snapshotStatusTracker.currentStatus();
    }

    @Override
    public SnapshotEntriesResult getSnapshotEntries(GetSnapshotEntriesQuery query) {
        if (query.offset() < MIN_OFFSET) {
            throw new IllegalArgumentException("offset must be greater than or equal to 0");
        }
        if (query.limit() < MIN_LIMIT || query.limit() > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and 1000, but was " + query.limit());
        }

        SnapshotBatches snapshot = resolveSnapshotBatch(query);
        long total = snapshotEntriesRepository.countBySnapshotId(snapshot.id());

        List<SnapshotEntryResult> items = snapshotEntriesRepository.findBySnapshotId(snapshot.id(), query.offset(), query.limit())
                .stream()
                .map(entry -> new SnapshotEntryResult(entry.rank(), entry.userId(), entry.score()))
                .toList();

        return new SnapshotEntriesResult(
                snapshot.leaderboardId(),
                snapshot.id(),
                snapshot.snapshotAt(),
                snapshot.topN(),
                total,
                items
        );
    }

    @Override
    @Transactional
    public CaptureSnapshotResult capture(CaptureSnapshotCommand command) {
        // 1) Hot Path(Redis)에서 snapshot 대상 Top-N을 읽는다.
        List<SnapshotRankingRow> rows = snapshotRankingQueryRepository.findTopRanks(command.leaderboardId(), command.topN());
        if (rows.isEmpty()) {
            // 2) Empty Guard: 빈 결과면 Cold Path 저장을 건너뛴다.
            return CaptureSnapshotResult.skipped(command.leaderboardId(), command.snapshotAt(), command.topN());
        }

        // 3) snapshot 배치 메타를 upsert하고 snapshotId를 확보한다.
        long snapshotId = snapshotBatchesRepository.upsert(
                new SnapshotBatchUpsertParam(command.leaderboardId(), command.snapshotAt(), command.topN())
        );

        // 4) Redis row를 DB upsert용 command 모델로 변환한다.
        List<SnapshotEntryUpsertParam> entryParams = rows.stream()
                .map(row -> new SnapshotEntryUpsertParam(snapshotId, row.userId(), row.rank(), row.score()))
                .toList();

        // 5) 해당 snapshotId 기준으로 entries를 교체한다(기존 삭제 + 배치 insert).
        snapshotEntriesRepository.replaceBySnapshotId(snapshotId, entryParams);

        // 6) 호출자가 로그/메트릭에 활용할 수 있도록 실행 결과 메타를 반환한다.
        return CaptureSnapshotResult.captured(
                command.leaderboardId(),
                command.snapshotAt(),
                command.topN(),
                snapshotId,
                rows.size()
        );
    }

    @Override
    @Transactional
    public RecoverLeaderboardSnapshotResult recover(RecoverLeaderboardSnapshotCommand command) {
        // PRD Cold Start Recovery: Redis ZSET이 이미 살아 있으면 PostgreSQL snapshot으로 덮어쓰지 않는다.
        long currentRankCount = snapshotRankingRestoreRepository.countRanks(command.leaderboardId());
        if (currentRankCount > 0) {
            return RecoverLeaderboardSnapshotResult.skipped(command.leaderboardId(), "REDIS_ALREADY_WARM");
        }

        // leaderboard별 최신 snapshot 1건만 복구 대상으로 사용한다.
        Optional<SnapshotBatches> latestSnapshot = snapshotBatchesRepository.findLatestByLeaderboardId(command.leaderboardId());
        if (latestSnapshot.isEmpty()) {
            return RecoverLeaderboardSnapshotResult.skipped(command.leaderboardId(), "SNAPSHOT_NOT_FOUND");
        }

        SnapshotBatches snapshot = latestSnapshot.get();
        int restoreLimit = Math.min(command.topN(), snapshot.topN());
        List<SnapshotEntries> entries = snapshotEntriesRepository.findBySnapshotId(snapshot.id(), 0, restoreLimit);
        if (entries.isEmpty()) {
            return RecoverLeaderboardSnapshotResult.skipped(command.leaderboardId(), "SNAPSHOT_ENTRIES_EMPTY");
        }

        // snapshot_entries의 rank/userId/score shape를 Redis ZSET 복구용 row로 변환한다.
        List<SnapshotRankingRow> rows = entries.stream()
                .map(entry -> new SnapshotRankingRow(entry.userId(), entry.rank(), entry.score()))
                .toList();

        snapshotRankingRestoreRepository.replaceTopRanks(command.leaderboardId(), rows);
        return RecoverLeaderboardSnapshotResult.recovered(command.leaderboardId(), rows.size(), snapshot.snapshotAt());
    }

    private SnapshotBatches resolveSnapshotBatch(GetSnapshotEntriesQuery query) {
        return (query.snapshotAt() == null
                ? snapshotBatchesRepository.findLatestByLeaderboardId(query.leaderboardId())
                : snapshotBatchesRepository.findByLeaderboardIdAndSnapshotAt(query.leaderboardId(), query.snapshotAt()))
                .orElseThrow(() -> new SnapshotNotFoundException(buildNotFoundMessage(query)));
    }

    private String buildNotFoundMessage(GetSnapshotEntriesQuery query) {
        if (query.snapshotAt() == null) {
            return "snapshot not found for leaderboardId=" + query.leaderboardId();
        }
        return "snapshot not found for leaderboardId=" + query.leaderboardId() + ", snapshotAt=" + query.snapshotAt();
    }
}
