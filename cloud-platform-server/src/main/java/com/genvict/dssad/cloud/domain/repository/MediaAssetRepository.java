package com.genvict.dssad.cloud.domain.repository;

import com.genvict.dssad.cloud.domain.entity.MediaAsset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 媒体资产仓储。 */
public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {

    List<MediaAsset> findByEventId(String eventId);

    Optional<MediaAsset> findFirstByEventIdAndCameraDirection(String eventId, String cameraDirection);

    /**
     * 历史视频查询（对应文档 6.3.2.1.2 接口）。
     *
     * <p>查询条件为「有重叠的片段」而非「完全落在区间内」：点播场景下用户给 00:00~24:00，
     * 而录像文件是 00:00~01:00 这种切片，必须用重叠判定
     * （{@code start_time <= queryEnd AND end_time >= queryStart}）才能把切片全部命中。
     */
    List<MediaAsset> findByVinAndCameraDirectionAndStartTimeLessThanEqualAndEndTimeGreaterThanEqualOrderByStartTimeAsc(
            String vin, String cameraDirection, Instant queryEnd, Instant queryStart);

    Page<MediaAsset> findByVinOrderByStartTimeDesc(String vin, Pageable pageable);

    Page<MediaAsset> findByEventIdOrderByCameraDirectionAsc(String eventId, Pageable pageable);

    long countByEventId(String eventId);

    /**
     * 统计某事故下已归档的<b>不同摄像头方向</b>数量。
     *
     * <p>用「不同方向数」而不是「文件数」判断取证是否收齐：车端可能因重发/重试
     * 对同一方向上传多次，若按文件数统计，2 个方向各传 2 次就会被误判为已收齐 4 个方向。
     */
    @Query("select count(distinct m.cameraDirection) from MediaAsset m where m.eventId = :eventId")
    long countDistinctDirectionByEventId(@Param("eventId") String eventId);
}
