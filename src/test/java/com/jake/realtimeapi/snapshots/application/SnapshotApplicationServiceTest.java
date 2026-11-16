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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SnapshotApplicationServiceTest {

    @Mock
    private SnapshotRankingQueryRepository snapshotRankingQueryRepository;

    @Mock
    private SnapshotBatchesRepository snapshotBatchesRepository;

    @Mock
    private SnapshotEntriesRepository snapshotEntriesRepository;

    @Mock
    private SnapshotRankingRestoreRepository snapshotRankingRestoreRepository;

    @Mock
    private SnapshotStatusTracker snapshotStatusTracker;

    @Mock
    private RankingWarmthRepository rankingWarmthRepository;

    @InjectMocks
    private SnapshotApplicationService snapshotApplicationService;

    @Test
    void findTopRanks_delegatesToQueryRepository() {
        UUID leaderboardId = UUID.randomUUID();
        List<SnapshotRankingRow> expected = List.of(new SnapshotRankingRow(10L, 1, 1000L));
        when(snapshotRankingQueryRepository.findTopRanks(leaderboardId, 1000)).thenReturn(expected);

        List<SnapshotRankingRow> actual = snapshotApplicationService.findTopRanks(leaderboardId, 1000);

        assertEquals(expected, actual);
    }

    @Test
    void getStatus_delegatesToStatusTracker() {
        SnapshotStatus expected = new SnapshotStatus(Instant.parse("2026-03-18T00:00:00Z"), 12L);
        when(snapshotStatusTracker.currentStatus()).thenReturn(expected);

        SnapshotStatus actual = snapshotApplicationService.getStatus();

        assertEquals(expected, actual);
    }

    @Test
    void getSnapshotEntries_readsLatestSnapshotWhenSnapshotAtIsNotProvided() {
        UUID leaderboardId = UUID.randomUUID();
        Instant snapshotAt = Instant.parse("2026-03-18T00:00:00Z");
        SnapshotBatches snapshot = new SnapshotBatches(42L, leaderboardId, snapshotAt, 1000, snapshotAt);

        when(snapshotBatchesRepository.findLatestByLeaderboardId(leaderboardId)).thenReturn(Optional.of(snapshot));
        when(snapshotEntriesRepository.countBySnapshotId(42L)).thenReturn(3L);
        when(snapshotEntriesRepository.findBySnapshotId(42L, 0, 2)).thenReturn(List.of(
                new SnapshotEntries(1L, 42L, 1, 10L, 1000L, snapshotAt),
                new SnapshotEntries(2L, 42L, 2, 20L, 800L, snapshotAt)
        ));

        SnapshotEntriesResult result = snapshotApplicationService.getSnapshotEntries(
                new GetSnapshotEntriesQuery(leaderboardId, null, 0, 2)
        );

        assertEquals(
                new SnapshotEntriesResult(
                        leaderboardId,
                        42L,
                        snapshotAt,
                        1000,
                        3L,
                        List.of(
                                new SnapshotEntryResult(1, 10L, 1000L),
                                new SnapshotEntryResult(2, 20L, 800L)
                        )
                ),
                result
        );
    }

    @Test
    void getSnapshotEntries_readsSpecificSnapshotWhenSnapshotAtIsProvided() {
        UUID leaderboardId = UUID.randomUUID();
        Instant snapshotAt = Instant.parse("2026-03-18T00:00:00Z");
        SnapshotBatches snapshot = new SnapshotBatches(42L, leaderboardId, snapshotAt, 1000, snapshotAt);

        when(snapshotBatchesRepository.findByLeaderboardIdAndSnapshotAt(leaderboardId, snapshotAt)).thenReturn(Optional.of(snapshot));
        when(snapshotEntriesRepository.countBySnapshotId(42L)).thenReturn(1L);
        when(snapshotEntriesRepository.findBySnapshotId(42L, 10, 1)).thenReturn(List.of(
                new SnapshotEntries(1L, 42L, 11, 30L, 400L, snapshotAt)
        ));

        SnapshotEntriesResult result = snapshotApplicationService.getSnapshotEntries(
                new GetSnapshotEntriesQuery(leaderboardId, snapshotAt, 10, 1)
        );

        assertEquals(42L, result.snapshotId());
        assertEquals(1L, result.total());
        assertEquals(List.of(new SnapshotEntryResult(11, 30L, 400L)), result.items());
    }

    @Test
    void getSnapshotEntries_throwsWhenSnapshotDoesNotExist() {
        UUID leaderboardId = UUID.randomUUID();
        when(snapshotBatchesRepository.findLatestByLeaderboardId(leaderboardId)).thenReturn(Optional.empty());

        assertThrows(
                SnapshotNotFoundException.class,
                () -> snapshotApplicationService.getSnapshotEntries(
                        new GetSnapshotEntriesQuery(leaderboardId, null, 0, 50)
                )
        );
    }

    @Test
    void capture_skipsWhenRedisTopRanksIsEmpty() {
        UUID leaderboardId = UUID.randomUUID();
        Instant snapshotAt = Instant.parse("2026-03-17T00:00:00Z");
        CaptureSnapshotCommand command = new CaptureSnapshotCommand(leaderboardId, snapshotAt, 1000);

        when(snapshotRankingQueryRepository.findTopRanks(leaderboardId, 1000)).thenReturn(List.of());

        CaptureSnapshotResult result = snapshotApplicationService.capture(command);

        assertTrue(result.skipped());
        assertEquals(0, result.rowCount());
        assertNull(result.snapshotId());
        verify(snapshotBatchesRepository, never()).upsert(any());
        verify(snapshotEntriesRepository, never()).replaceBySnapshotId(anyLong(), anyList());
    }

    @Test
    void capture_upsertsBatchThenReplacesEntriesWhenRowsExist() {
        UUID leaderboardId = UUID.randomUUID();
        Instant snapshotAt = Instant.parse("2026-03-17T00:00:00Z");
        CaptureSnapshotCommand command = new CaptureSnapshotCommand(leaderboardId, snapshotAt, 1000);

        List<SnapshotRankingRow> rows = List.of(
                new SnapshotRankingRow(10L, 1, 1000L),
                new SnapshotRankingRow(20L, 2, 800L),
                new SnapshotRankingRow(30L, 2, 800L)
        );
        when(snapshotRankingQueryRepository.findTopRanks(leaderboardId, 1000)).thenReturn(rows);
        when(snapshotBatchesRepository.upsert(new SnapshotBatchUpsertParam(leaderboardId, snapshotAt, 1000)))
                .thenReturn(42L);

        CaptureSnapshotResult result = snapshotApplicationService.capture(command);

        assertEquals(false, result.skipped());
        assertEquals(42L, result.snapshotId());
        assertEquals(3, result.rowCount());

        verify(snapshotEntriesRepository).replaceBySnapshotId(eq(42L), eq(
                List.of(
                        new SnapshotEntryUpsertParam(42L, 10L, 1, 1000L),
                        new SnapshotEntryUpsertParam(42L, 20L, 2, 800L),
                        new SnapshotEntryUpsertParam(42L, 30L, 2, 800L)
                ))
        );
    }

    @Test
    void capture_refusesToOverwriteWithFewerPlayersThanTheLastSnapshot() {
        // Redis가 랭킹을 잃은 뒤 한 명만 다시 쌓였다. 그대로 저장하면 스냅샷에서도 두 명이 사라진다.
        UUID leaderboardId = UUID.randomUUID();
        Instant snapshotAt = Instant.parse("2026-03-17T00:00:30Z");
        SnapshotBatches previous = new SnapshotBatches(41L, leaderboardId, snapshotAt.minusSeconds(30), 1000, snapshotAt);
        when(snapshotRankingQueryRepository.findTopRanks(leaderboardId, 1000)).thenReturn(List.of(new SnapshotRankingRow(10L, 1, 5L)));
        when(snapshotBatchesRepository.findLatestByLeaderboardId(leaderboardId)).thenReturn(Optional.of(previous));
        when(snapshotEntriesRepository.countBySnapshotId(41L)).thenReturn(3L);

        CaptureSnapshotResult result = snapshotApplicationService.capture(new CaptureSnapshotCommand(leaderboardId, snapshotAt, 1000));

        assertTrue(result.skipped());
        assertEquals("SHRUNK", result.skipReason());
        verify(snapshotBatchesRepository, never()).upsert(any());
    }

    @Test
    void capture_doesNotTreatALowerTopNAsShrinking() {
        // 운영자가 topN을 1,000에서 500으로 줄인 것은 데이터 유실이 아니다
        UUID leaderboardId = UUID.randomUUID();
        Instant snapshotAt = Instant.parse("2026-03-17T00:00:30Z");
        SnapshotBatches previous = new SnapshotBatches(41L, leaderboardId, snapshotAt.minusSeconds(30), 1000, snapshotAt);
        List<SnapshotRankingRow> rows = IntStream.rangeClosed(1, 500)
                .mapToObj(i -> new SnapshotRankingRow(i, i, 10_000L - i)).toList();
        when(snapshotRankingQueryRepository.findTopRanks(leaderboardId, 500)).thenReturn(rows);
        when(snapshotBatchesRepository.findLatestByLeaderboardId(leaderboardId)).thenReturn(Optional.of(previous));
        when(snapshotEntriesRepository.countBySnapshotId(41L)).thenReturn(1000L);
        when(snapshotBatchesRepository.upsert(any())).thenReturn(42L);

        CaptureSnapshotResult result = snapshotApplicationService.capture(new CaptureSnapshotCommand(leaderboardId, snapshotAt, 500));

        assertEquals(false, result.skipped());
        assertEquals(500, result.rowCount());
    }

    @Test
    void recover_skipsWhenWarmKeyExists() {
        UUID leaderboardId = UUID.randomUUID();
        when(rankingWarmthRepository.isWarm(leaderboardId)).thenReturn(true);

        RecoverLeaderboardSnapshotResult result = snapshotApplicationService.recover(
                new RecoverLeaderboardSnapshotCommand(leaderboardId, 1000)
        );

        assertTrue(!result.recovered());
        assertEquals("WARM", result.reason());
        verify(snapshotBatchesRepository, never()).findLatestByLeaderboardId(any());
    }

    @Test
    void recover_marksWarmWhenThereIsNoSnapshotToRestore() {
        // 되살릴 것이 없을 때 cold로 남겨 두면 쓰기가 영원히 막힌다
        UUID leaderboardId = UUID.randomUUID();
        when(snapshotBatchesRepository.findLatestByLeaderboardId(leaderboardId)).thenReturn(Optional.empty());

        RecoverLeaderboardSnapshotResult result = snapshotApplicationService.recover(
                new RecoverLeaderboardSnapshotCommand(leaderboardId, 1000)
        );

        assertEquals("SNAPSHOT_NOT_FOUND", result.reason());
        verify(rankingWarmthRepository).markWarm(leaderboardId);
    }

    @Test
    void recover_marksWarmWithoutRestoringWhenRedisAlreadyHoldsAtLeastTheSnapshot() {
        // warm 키 도입 배포 직후: 키는 없지만 데이터는 온전하다. 30초 전 스냅샷으로 덮어쓰면 안 된다.
        UUID leaderboardId = UUID.randomUUID();
        Instant snapshotAt = Instant.parse("2026-03-20T00:00:00Z");
        when(snapshotBatchesRepository.findLatestByLeaderboardId(leaderboardId))
                .thenReturn(Optional.of(new SnapshotBatches(42L, leaderboardId, snapshotAt, 1000, snapshotAt)));
        when(snapshotEntriesRepository.findBySnapshotId(42L, 0, 1000)).thenReturn(List.of(
                new SnapshotEntries(1L, 42L, 1, 10L, 1000L, snapshotAt),
                new SnapshotEntries(2L, 42L, 2, 20L, 800L, snapshotAt)
        ));
        when(snapshotRankingRestoreRepository.countRanks(leaderboardId)).thenReturn(3L);

        RecoverLeaderboardSnapshotResult result = snapshotApplicationService.recover(
                new RecoverLeaderboardSnapshotCommand(leaderboardId, 1000)
        );

        assertEquals("REDIS_ALREADY_WARM", result.reason());
        verify(snapshotRankingRestoreRepository, never()).replaceTopRanks(any(), anyList());
        verify(rankingWarmthRepository).markWarm(leaderboardId);
    }

    @Test
    void recover_restoresTheSnapshotThenMarksWarm() {
        UUID leaderboardId = UUID.randomUUID();
        Instant snapshotAt = Instant.parse("2026-03-20T00:00:00Z");
        SnapshotBatches snapshot = new SnapshotBatches(42L, leaderboardId, snapshotAt, 1000, snapshotAt);

        when(snapshotRankingRestoreRepository.countRanks(leaderboardId)).thenReturn(0L);
        when(snapshotBatchesRepository.findLatestByLeaderboardId(leaderboardId)).thenReturn(Optional.of(snapshot));
        when(snapshotEntriesRepository.findBySnapshotId(42L, 0, 1000)).thenReturn(List.of(
                new SnapshotEntries(1L, 42L, 1, 10L, 1000L, snapshotAt),
                new SnapshotEntries(2L, 42L, 2, 20L, 800L, snapshotAt)
        ));

        RecoverLeaderboardSnapshotResult result = snapshotApplicationService.recover(
                new RecoverLeaderboardSnapshotCommand(leaderboardId, 1000)
        );

        assertTrue(result.recovered());
        assertEquals(2L, result.restoredRowCount());
        assertEquals(snapshotAt, result.snapshotAt());
        // warm으로 바꾸기 전에 랭킹을 다 채운다. 그 사이 읽기는 스냅샷으로, 쓰기는 503으로 간다.
        InOrder inOrder = inOrder(snapshotRankingRestoreRepository, rankingWarmthRepository);
        inOrder.verify(snapshotRankingRestoreRepository).replaceTopRanks(eq(leaderboardId), eq(List.of(
                new SnapshotRankingRow(10L, 1, 1000L),
                new SnapshotRankingRow(20L, 2, 800L)
        )));
        inOrder.verify(rankingWarmthRepository).markWarm(leaderboardId);
    }
}
