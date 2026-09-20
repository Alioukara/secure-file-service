package io.github.alioukara.sfs.repository;

import io.github.alioukara.sfs.domain.FileStatus;
import io.github.alioukara.sfs.domain.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.UUID;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {

    @Query("select coalesce(sum(f.sizeBytes), 0) from StoredFile f where f.status in :statuses")
    long sumSizeByStatusIn(@Param("statuses") Collection<FileStatus> statuses);
}
