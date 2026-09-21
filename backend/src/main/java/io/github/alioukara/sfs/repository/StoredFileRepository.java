package io.github.alioukara.sfs.repository;

import io.github.alioukara.sfs.domain.FileStatus;
import io.github.alioukara.sfs.domain.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {

    @Query("select coalesce(sum(f.sizeBytes), 0) from StoredFile f where f.status in :statuses")
    long sumSizeByStatusIn(@Param("statuses") Collection<FileStatus> statuses);

    /**
     * SKIP LOCKED lets several instances sweep at once without waiting on each
     * other: a row another instance is already handling is stepped over instead
     * of blocking. Native because JPQL has no such clause, and MySQL 8 only —
     * H2 does not support it, which is why the sweep queries are covered by the
     * MySQL test rather than the embedded one.
     */
    @Query(value = """
            select id from stored_file
            where status = 'PENDING'
            order by created_at
            limit :batchSize
            for update skip locked
            """, nativeQuery = true)
    List<UUID> lockQueued(@Param("batchSize") int batchSize);

    @Query(value = """
            select id from stored_file
            where status = 'SCANNING' and scan_started_at < :expiredBefore
            order by scan_started_at
            limit :batchSize
            for update skip locked
            """, nativeQuery = true)
    List<UUID> lockExpiredLeases(@Param("expiredBefore") Instant expiredBefore,
                                 @Param("batchSize") int batchSize);

    @Query(value = """
            select id from stored_file
            where status = 'SCAN_FAILED' and updated_at < :readyBefore
            order by updated_at
            limit :batchSize
            for update skip locked
            """, nativeQuery = true)
    List<UUID> lockRetryable(@Param("readyBefore") Instant readyBefore,
                             @Param("batchSize") int batchSize);
}
