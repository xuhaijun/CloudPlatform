package com.genvict.dssad.cloud.domain.repository;

import com.genvict.dssad.cloud.domain.entity.TrackTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

/** 运营任务仓储。 */
public interface TrackTaskRepository extends JpaRepository<TrackTask, Long> {

    Optional<TrackTask> findByTaskId(String taskId);

    Page<TrackTask> findByVinOrderByStartedAtDesc(String vin, Pageable pageable);

    Page<TrackTask> findByStatusOrderByStartedAtDesc(TrackTask.TaskStatus status, Pageable pageable);

    Page<TrackTask> findByStartedAtBetweenOrderByStartedAtDesc(Instant from, Instant to, Pageable pageable);

    long countByStatus(TrackTask.TaskStatus status);
}
