package com.jake.realtimeapi.snapshots.application;

import com.jake.realtimeapi.events.domain.repository.RankingWarmthRepository;
import com.jake.realtimeapi.snapshots.application.command.CaptureSnapshotCommand;
import com.jake.realtimeapi.snapshots.application.command.RecoverLeaderboardSnapshotCommand;
import com.jake.realtimeapi.snapshots.application.model.CaptureSnapshotResult;
import com.jake.realtimeapi.snapshots.application.model.RecoverLeaderboardSnapshotResult;
import com.jake.realtimeapi.snapshots.application.model.SnapshotEntriesResult;
import com.jake.realtimeapi.snapshots.application.model.SnapshotEntryResult;
import com.jake.realtimeapi.snapshots.application.model.SnapshotStatus;
import com.jake.realtimeapi.snapshots.application.query.GetSnapshotEntriesQuery;
import com.jake.realtimeapi.snapshots.application.usecase.CaptureSnapshotUseCase;
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
public class SnapshotApplicationService implements        CaptureSnapshotUseCase,
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
    private final RankingWarmthRepository rankingWarmthRepository;

    public SnapshotApplicationService(
            SnapshotRankingQueryRepository snapshotRankingQueryRepository,
            SnapshotBatchesRepository snapshotBatchesRepository,
            SnapshotEntriesRepository snapshotEntriesRepository,
            SnapshotRankingRestoreRepository snapshotRankingRestoreRepository,
            SnapshotStatusTracker snapshotStatusTracker,
            RankingWarmthRepository rankingWarmthRepository
    ) {
        this.snapshotRankingQueryRepository = snapshotRankingQueryRepository;
        this.snapshotBatchesRepository = snapshotBatchesRepository;
        this.snapshotEntriesRepository = snapshotEntriesRepository;
        this.snapshotRankingRestoreRepository = snapshotRankingRestoreRepository;
        this.snapshotStatusTracker = snapshotStatusTracker;
        this.rankingWarmthRepository = rankingWarmthRepository;
    }

    public List<SnapshotRankingRow> findTopRanks(UUID leaderboardId, int limit) {
        return snapshotRankingQueryRepository.findTopRanks(leaderboardId, limit);
    }

    @Override
    public SnapshotStatus getStatus() {
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
        List<SnapshotRankingRow> rows = snapshotRankingQueryRepository.findTopRanks(command.leaderboardId(), command.topN());
        if (rows.isEmpty()) {
            return CaptureSnapshotResult.skipped(command.leaderboardId(), command.snapshotAt(), command.topN());
        }

        // Shrink Guard: 플레이어는 지워지지 않고 점수는 늘기만 하므로 Top-N 행 수는 줄지 않는다.
        // 줄었다면 Redis가 데이터를 잃은 뒤 일부만 다시 쌓인 것이라, 덮어쓰면 스냅샷에서도 플레이어가 사라진다.
        Optional<SnapshotBatches> previous = snapshotBatchesRepository.findLatestByLeaderboardId(command.leaderboardId());
        if (previous.isPresent()) {
            long previousRows = Math.min(snapshotEntriesRepository.countBySnapshotId(previous.get().id()), command.topN());
            if (rows.size() < previousRows) {
                return CaptureSnapshotResult.shrunk(command.leaderboardId(), command.snapshotAt(), command.topN(), rows.size());
            }
        }

        long snapshotId = snapshotBatchesRepository.upsert(
                new SnapshotBatchUpsertParam(command.leaderboardId(), command.snapshotAt(), command.topN())
        );

        List<SnapshotEntryUpsertParam> entryParams = rows.stream()
                .map(row -> new SnapshotEntryUpsertParam(snapshotId, row.userId(), row.rank(), row.score()))
                .toList();

        snapshotEntriesRepository.replaceBySnapshotId(snapshotId, entryParams);

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
        UUID leaderboardId = command.leaderboardId();
        // warm 키는 랭킹과 함께 사라진다. 있으면 Redis 데이터가 온전하다.
        if (rankingWarmthRepository.isWarm(leaderboardId)) {
            return RecoverLeaderboardSnapshotResult.skipped(leaderboardId, "WARM");
        }

        // leaderboard별 최신 snapshot 1건만 복구 대상으로 사용한다.
        Optional<SnapshotBatches> latestSnapshot = snapshotBatchesRepository.findLatestByLeaderboardId(leaderboardId);
        if (latestSnapshot.isEmpty()) {
            // 되살릴 것이 없다. 계속 cold로 두면 쓰기가 영원히 막히므로 빈 랭킹으로 다시 시작한다.
            rankingWarmthRepository.markWarm(leaderboardId);
            return RecoverLeaderboardSnapshotResult.skipped(leaderboardId, "SNAPSHOT_NOT_FOUND");
        }

        SnapshotBatches snapshot = latestSnapshot.get();
        int restoreLimit = Math.min(command.topN(), snapshot.topN());
        List<SnapshotEntries> entries = snapshotEntriesRepository.findBySnapshotId(snapshot.id(), 0, restoreLimit);
        if (entries.isEmpty()) {
            rankingWarmthRepository.markWarm(leaderboardId);
            return RecoverLeaderboardSnapshotResult.skipped(leaderboardId, "SNAPSHOT_ENTRIES_EMPTY");
        }

        // cold인 동안 쓰기는 Lua가 거부하므로, 데이터를 잃은 Redis의 랭킹은 비어 있다.
        // 스냅샷 이상으로 차 있다면 warm 키가 생기기 전부터 있던 온전한 데이터다(warm 키 도입 배포 직후).
        if (snapshotRankingRestoreRepository.countRanks(leaderboardId) >= entries.size()) {
            rankingWarmthRepository.markWarm(leaderboardId);
            return RecoverLeaderboardSnapshotResult.skipped(leaderboardId, "REDIS_ALREADY_WARM");
        }

        List<SnapshotRankingRow> rows = entries.stream()
                .map(entry -> new SnapshotRankingRow(entry.userId(), entry.rank(), entry.score()))
                .toList();

        snapshotRankingRestoreRepository.replaceTopRanks(leaderboardId, rows);
        // 랭킹을 다 채운 뒤에 warm으로 바꾼다. 그 전까지 읽기는 스냅샷으로, 쓰기는 503으로 간다.
        rankingWarmthRepository.markWarm(leaderboardId);
        return RecoverLeaderboardSnapshotResult.recovered(leaderboardId, rows.size(), snapshot.snapshotAt());
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
