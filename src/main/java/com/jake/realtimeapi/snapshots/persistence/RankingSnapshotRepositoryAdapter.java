package com.jake.realtimeapi.snapshots.persistence;

import com.jake.realtimeapi.events.domain.model.RankingSnapshot;
import com.jake.realtimeapi.events.domain.model.TopRankItem;
import com.jake.realtimeapi.events.domain.repository.RankingSnapshotRepository;
import com.jake.realtimeapi.snapshots.domain.repository.SnapshotBatchesRepository;
import com.jake.realtimeapi.snapshots.domain.repository.SnapshotEntriesRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public class RankingSnapshotRepositoryAdapter implements RankingSnapshotRepository {

    private final SnapshotBatchesRepository snapshotBatchesRepository;
    private final SnapshotEntriesRepository snapshotEntriesRepository;

    public RankingSnapshotRepositoryAdapter(
            SnapshotBatchesRepository snapshotBatchesRepository,
            SnapshotEntriesRepository snapshotEntriesRepository
    ) {
        this.snapshotBatchesRepository = snapshotBatchesRepository;
        this.snapshotEntriesRepository = snapshotEntriesRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RankingSnapshot> findLatest(UUID leaderboardId) {
        return snapshotBatchesRepository.findLatestByLeaderboardId(leaderboardId)
                .map(batch -> new RankingSnapshot(
                        batch.snapshotAt(),
                        batch.topN(),
                        snapshotEntriesRepository.findBySnapshotId(batch.id(), 0, batch.topN()).stream()
                                .map(entry -> new TopRankItem(entry.rank(), entry.userId(), entry.score()))
                                .toList()
                ));
    }
}
