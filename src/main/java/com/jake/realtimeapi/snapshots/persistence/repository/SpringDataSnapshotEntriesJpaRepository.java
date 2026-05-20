package com.jake.realtimeapi.snapshots.persistence.repository;

import com.jake.realtimeapi.snapshots.persistence.entity.SnapshotEntriesJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpringDataSnapshotEntriesJpaRepository extends JpaRepository<SnapshotEntriesJpaEntity, Long> {

    long countBySnapshotId(long snapshotId);

    Optional<SnapshotEntriesJpaEntity> findBySnapshotIdAndUserId(long snapshotId, long userId);

    @Query(value = """
        SELECT *
        FROM snapshot_entries
        WHERE snapshot_id = :snapshotId
        ORDER BY rank ASC, user_id ASC
        OFFSET :offset LIMIT :limit
        """, nativeQuery = true)
    List<SnapshotEntriesJpaEntity> findBySnapshotId(
            @Param("snapshotId") long snapshotId,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    @Modifying
    @Query(value = "DELETE FROM snapshot_entries WHERE snapshot_id = :snapshotId", nativeQuery = true)
    int deleteBySnapshotId(@Param("snapshotId") long snapshotId);


//    TODO
//    @Query(value = """
//        SELECT *
//        FROM snapshot_entries
//        WHERE user_id = :userId
//        ORDER BY created_at ASC
//        OFFSET :offset LIMIT :limit
//        """, nativeQuery = true)
//    List<SnapshotEntriesJpaEntity> findByUserId(
//            @Param("userId") Long userId,
//            @Param("offset") int offset,
//            @Param("limit") int limit
//    );
}
