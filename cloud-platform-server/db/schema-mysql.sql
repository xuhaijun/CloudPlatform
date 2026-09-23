-- ============================================================================
--  车路通 DSSAD 产品云平台 —— MySQL 建表脚本（生产环境）
--
--  适配版本 : MySQL 8.0.17+ / 字符集 utf8mb4
--  生成方式 : 由 Hibernate 6.6 元数据导出后人工规范化
--             mvn test -Dtest=SchemaDdlGeneratorTest -Dddl.gen=true
--             产出 db/schema-mysql.generated.sql 作为比对基线
--
--  ⚠️ 维护约定（务必遵守）：
--  1. 本脚本的**列名与列类型必须与 JPA 实体完全一致**，因为生产环境使用
--     spring.jpa.hibernate.ddl-auto=validate，任何不一致都会导致启动失败。
--     改实体后重新执行上面的生成命令，与本文件做 diff 再提交。
--  2. 索引与分区可以手工增强（Hibernate 不会生成分区），但列类型不要手工改。
--  3. float(53) 是 MySQL 对 DOUBLE 的写法（p>24 即转为 DOUBLE），无需修改；
--     Hibernate 的校验器按类型族比较，改成 double 也可，但保持生成值最省心。
--  4. enum(...) 列用于状态字段，新增枚举值需 ALTER TABLE（见文末变更说明）。
--
--  执行：mysql -h$DB_HOST -u$DB_USERNAME -p $DB_NAME < db/schema-mysql.sql
--
--  ▸ 数据生命周期（建库后必须一并确认，否则流水表会无限增长）：
--      表                          增长速率(1000台车)   清理任务                     配置项
--      t_vehicle_track_point       ≈8640 万行/天        TelemetryRetentionService   dssad.retention.track-point-days
--      t_vehicle_state_snapshot    ≈144 万行/天         同上                        dssad.retention.state-snapshot-days
--      t_mqtt_message_log          ≈报文量 × 采样率      MqttAuditService.purge      dssad.audit.retention-days
--    三张表的清理时间刻意错开（00:05 地图拉取 / 03:00 留痕 / 03:30 遥测），避免同时争抢 IO。
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================================
-- 1. 车辆档案（准静态参数的"最新值"冗余在这里，避免列表页 JOIN）
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_vehicle (
    vin             VARCHAR(32)  NOT NULL COMMENT '车辆 VIN（主键，文档唯一编号）',
    created_at      DATETIME(6)  NOT NULL COMMENT '平台首次建档时间',
    updated_at      DATETIME(6)  NOT NULL COMMENT '最后更新时间',
    accident_count  BIGINT       NOT NULL DEFAULT 0 COMMENT '累计事故数（冗余计数，避免列表页聚合）',
    fault_count     BIGINT       NOT NULL DEFAULT 0 COMMENT '累计故障条数（冗余计数）',
    online          BIT          NULL COMMENT '是否在线（心跳超时由定时任务置 false）',
    vehicle_type    VARCHAR(16)  NULL COMMENT '车端类型 low_speed/high_speed（决定车云 Topic 段）',
    plate_no        VARCHAR(16)  NULL COMMENT '车牌号',
    model           VARCHAR(64)  NULL COMMENT '车型',
    device_sn       VARCHAR(64)  NULL COMMENT '设备序列号',
    hw_version      VARCHAR(64)  NULL COMMENT '硬件版本（来自 inh 报文）',
    ad_version      VARCHAR(64)  NULL COMMENT '自动驾驶软件版本',
    com_type        VARCHAR(8)   NULL COMMENT '通信类型',
    time_sync       VARCHAR(8)   NULL COMMENT '时间同步方式',
    enterprise_id   VARCHAR(32)  NULL COMMENT '所属企业 ID',
    last_inh_at     DATETIME(6)  NULL COMMENT '最后收到 inh（初始化）时间',
    last_base_at    DATETIME(6)  NULL COMMENT '最后收到 low-base 时间',
    last_state_at   DATETIME(6)  NULL COMMENT '最后收到 low-state 时间',
    last_online_at  DATETIME(6)  NULL COMMENT '最后心跳时间',
    version         BIGINT       NULL COMMENT 'JPA 乐观锁版本号',
    PRIMARY KEY (vin),
    INDEX idx_vehicle_enterprise (enterprise_id),
    -- 在线列表按「在线 + 最后心跳」查询，复合索引可同时覆盖过滤与排序
    INDEX idx_vehicle_online (online, last_base_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '车辆档案（主档）';

-- ============================================================================
-- 2. 准静态参数变更历史（inh 报文，仅变更时写入，量小但需保留全量）
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_vehicle_static_param (
    id           BIGINT       NOT NULL COMMENT '主键（应用侧雪花 ID，非自增）',
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    vin          VARCHAR(32)  NOT NULL COMMENT '车辆 VIN',
    reported_at  DATETIME(6)  NOT NULL COMMENT '车端上报时间（业务时间）',
    msg_id       VARCHAR(64)  NULL COMMENT '报文编号',
    hw_version   VARCHAR(64)  NULL,
    ad_version   VARCHAR(64)  NULL,
    com_type     VARCHAR(8)   NULL,
    time_sync    VARCHAR(8)   NULL,
    PRIMARY KEY (id),
    INDEX idx_inh_vin_time (vin, reported_at),
    INDEX idx_inh_msg_id (msg_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '车辆准静态参数变更历史';

-- ============================================================================
-- 3. 运行状态快照（low-state，约 0.1~1Hz，写入量大）
--    主键为应用侧雪花 ID：不使用自增，才能配合 rewriteBatchedStatements 做真批量插入
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_vehicle_state_snapshot (
    id                    BIGINT       NOT NULL COMMENT '主键（应用侧雪花 ID）',
    vin                   VARCHAR(32)  NOT NULL COMMENT '车辆 VIN',
    reported_at           DATETIME(6)  NOT NULL COMMENT '上报时间（业务时间）',
    msg_id                VARCHAR(64)  NULL,
    charge_state          VARCHAR(8)   NULL COMMENT '充电状态',
    soc                   FLOAT(53)    NULL COMMENT '剩余电量百分比',
    battery_capacity      FLOAT(53)    NULL COMMENT '电池容量 kWh',
    endurance             FLOAT(53)    NULL COMMENT '续航里程 km',
    mileage               FLOAT(53)    NULL COMMENT '总里程 km',
    vehicle_total_weight  FLOAT(53)    NULL COMMENT '车辆总重 kg',
    temperature           INT          NULL COMMENT '温度',
    gnss_status           VARCHAR(8)   NULL COMMENT '定位状态',
    tire_pressure         VARCHAR(512) NULL COMMENT '胎压（多轮 JSON/文本）',
    PRIMARY KEY (id),
    INDEX idx_state_vin_time (vin, reported_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '车辆运行状态快照';

-- ============================================================================
-- 4. 轨迹点（low-base，1Hz —— 全库写入量最大的表）
--    极窄行设计：只有 9 个业务列，全部为定长数值，单行约 80 字节
--    1000 台车 × 86400 点/天 ≈ 8640 万行/天，必须配合保留策略清理
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_vehicle_track_point (
    id            BIGINT      NOT NULL COMMENT '主键（应用侧雪花 ID，按时间递增）',
    vin           VARCHAR(32) NOT NULL COMMENT '车辆 VIN',
    ts            BIGINT      NOT NULL COMMENT '定位时间（13 位毫秒时间戳，业务时间）',
    longitude     FLOAT(53)   NULL COMMENT '经度（GCJ-02）',
    latitude      FLOAT(53)   NULL COMMENT '纬度（GCJ-02）',
    altitude      FLOAT(53)   NULL COMMENT '海拔 m',
    speed         FLOAT(53)   NULL COMMENT '速度 km/h',
    acc_speed     FLOAT(53)   NULL COMMENT '加速度 m/s²',
    heading       FLOAT(53)   NULL COMMENT '航向角',
    driving_mode  INT         NULL COMMENT '驾驶模式',
    PRIMARY KEY (id),
    -- 轨迹回放的主查询：按 VIN + 时间范围扫描，此索引是性能生命线
    INDEX idx_track_vin_ts (vin, ts),
    INDEX idx_track_ts (ts)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '车辆轨迹点（1Hz，高写入量表）';

-- ============================================================================
-- 5. 事故事件（含媒体取证状态机）
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_accident_event (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    event_id              VARCHAR(64)  NOT NULL COMMENT '事故 ID（车端生成，全局唯一）',
    vin                   VARCHAR(32)  NOT NULL COMMENT '车辆 VIN',
    enterprise_id         VARCHAR(32)  NULL,
    msg_id                VARCHAR(64)  NULL COMMENT '首个事故上报报文编号',
    occurred_at           DATETIME(6)  NOT NULL COMMENT '事发时间',
    accident_desc         TEXT         NULL COMMENT '事故描述（车端原文）',
    latitude              FLOAT(53)    NULL COMMENT '事发纬度',
    longitude             FLOAT(53)    NULL COMMENT '事发经度',
    media_status          ENUM ('MEDIA_ACCEPTED','MEDIA_ARCHIVED','MEDIA_FAILED','MEDIA_REQUESTED','REPORTED') NOT NULL
                                       COMMENT '媒体取证状态机：REPORTED→MEDIA_REQUESTED→MEDIA_ACCEPTED→MEDIA_ARCHIVED',
    media_requested_at    DATETIME(6)  NULL COMMENT '下发媒体请求时间',
    media_count           INT          NOT NULL DEFAULT 0 COMMENT '已归档视频文件数',
    media_expected_count  INT          NOT NULL DEFAULT 0 COMMENT '本次请求要求的方向数（收齐判定依据）',
    media_request_msg_id  VARCHAR(64)  NULL COMMENT '媒体请求报文编号（与车端应答/上传配对）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_accident_event_id (event_id),
    INDEX idx_accident_vin_time (vin, occurred_at),
    -- 支撑"待取证"看板：按状态 + 时间筛选
    INDEX idx_accident_status (media_status, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '事故事件与取证状态';

-- ============================================================================
-- 6/7. 故障主记录 + 明细（一次上报产生 1 主 + N 明细）
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_fault_record (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    vin           VARCHAR(32) NOT NULL COMMENT '车辆 VIN',
    msg_id        VARCHAR(64) NULL,
    reported_at   DATETIME(6) NOT NULL COMMENT '上报时间',
    error_num     INT         NULL COMMENT '本次上报的故障条数',
    max_severity  INT         NULL COMMENT '最严重等级（冗余，列表按此排序避免 JOIN 聚合）',
    latitude      FLOAT(53)   NULL,
    longitude     FLOAT(53)   NULL,
    PRIMARY KEY (id),
    INDEX idx_fault_vin_time (vin, reported_at),
    INDEX idx_fault_severity (max_severity, reported_at),
    -- 「近 N 天故障统计」只按 reported_at 过滤（父表聚合 + 明细表 JOIN 的驱动条件）
    INDEX idx_fault_time (reported_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '故障上报主记录';

CREATE TABLE IF NOT EXISTS t_fault_item (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    fault_record_id  BIGINT       NOT NULL COMMENT '所属故障主记录',
    vin              VARCHAR(32)  NOT NULL COMMENT '车辆 VIN（冗余，便于按车直接查明细）',
    error_code       VARCHAR(16)  NOT NULL COMMENT '故障码（对应字典 A.1 的 28 个码）',
    error_severity   VARCHAR(8)   NULL COMMENT '严重等级 0~7：0~1 无影响 / 2~3 可恢复 / 4~5 需人工处理 / 6~7 严重不可恢复',
    error_reason     VARCHAR(255) NULL COMMENT '故障原因描述',
    error_timestamp  BIGINT       NULL COMMENT '车端故障时间戳（毫秒）',
    msg_count        INT          NULL COMMENT '同码重复次数',
    PRIMARY KEY (id),
    -- 支撑"故障码 TOP N"统计
    INDEX idx_fault_item_code (error_code, error_timestamp),
    INDEX idx_fault_item_record (fault_record_id),
    CONSTRAINT FK_fault_item_record FOREIGN KEY (fault_record_id) REFERENCES t_fault_record (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '故障明细项';

-- ============================================================================
-- 8. 轨迹跟踪任务（task 报文的开始/结束配对）
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_track_task (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    task_id           VARCHAR(64)  NOT NULL COMMENT '任务 ID（平台生成，唯一）',
    vin               VARCHAR(32)  NOT NULL,
    enterprise_id     VARCHAR(32)  NULL,
    status            ENUM ('FINISHED','ORPHAN_FINISH','RUNNING') NOT NULL
                                   COMMENT 'RUNNING 进行中 / FINISHED 正常结束 / ORPHAN_FINISH 仅收到结束报文',
    start_position    VARCHAR(255) NULL COMMENT '起点描述',
    start_latitude    FLOAT(53)    NULL,
    start_longitude   FLOAT(53)    NULL,
    end_position      VARCHAR(255) NULL COMMENT '终点描述',
    end_latitude      FLOAT(53)    NULL,
    end_longitude     FLOAT(53)    NULL,
    started_at        DATETIME(6)  NULL,
    finished_at       DATETIME(6)  NULL,
    duration_seconds  BIGINT       NULL COMMENT '任务时长（秒）',
    last_msg_id       VARCHAR(64)  NULL COMMENT '最后一次关联报文编号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_id (task_id),
    INDEX idx_task_vin_status (vin, status),
    INDEX idx_task_started_at (started_at),
    -- 运营页默认视图按 status 过滤 + 按 started_at 排序，前两个索引都用不上
    INDEX idx_task_status_time (status, started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '轨迹跟踪任务';

-- ============================================================================
-- 9. 远程驾驶接管记录
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_remote_driving_record (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    created_at         DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    vin                VARCHAR(32) NOT NULL,
    enterprise_id      VARCHAR(32) NULL,
    msg_id             VARCHAR(64) NULL,
    driving_type       VARCHAR(8)  NOT NULL COMMENT '1 发起接管 / 2 结束接管',
    occurred_at        DATETIME(6) NOT NULL COMMENT '事件发生时间',
    closed             BIT         NOT NULL DEFAULT 0 COMMENT '是否已结束（列表默认只看进行中）',
    remote_cockpit_no  VARCHAR(64) NULL COMMENT '远驾舱编号',
    driver             VARCHAR(64) NULL COMMENT '驾驶员姓名',
    driver_card        VARCHAR(64) NULL COMMENT '驾驶证号',
    duration_seconds   BIGINT      NULL COMMENT '接管时长（秒）',
    PRIMARY KEY (id),
    INDEX idx_remote_vin_time (vin, occurred_at),
    INDEX idx_remote_type (driving_type, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '远程驾驶接管记录';

-- ============================================================================
-- 10. 地图阻断设施（mapehn 推送 + 监管平台拉取）
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_map_barrier (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    enterprise_id   VARCHAR(32)  NULL,
    area_code       VARCHAR(64)  NULL COMMENT '区域编码（拉取时按区域过滤）',
    barrier_id      VARCHAR(64)  NOT NULL COMMENT '阻断设施 ID',
    barrier_type    VARCHAR(8)   NOT NULL COMMENT '阻断类型：20 禁行区域 / 21 限速区域',
    barrier_name    VARCHAR(128) NULL COMMENT '名称，如"学校区域2"',
    barrier_polygon TEXT         NULL COMMENT '多边形顶点 JSON [[lat,lng],...]，首尾闭环',
    date_type       VARCHAR(8)   NULL COMMENT '日期类型（工作日/节假日等）',
    time_range      TEXT         NULL COMMENT '生效时间段 JSON [["7:00","9:00"],...]',
    speed_limit     FLOAT(53)    NULL COMMENT '限速 km/h',
    effective_date  DATE         NULL COMMENT '生效日期（幂等键组成部分）',
    source          VARCHAR(8)   NULL COMMENT '来源 PUSH 推送 / PULL 拉取',
    msg_id          VARCHAR(64)  NULL,
    received_at     DATETIME(6)  NULL COMMENT '接收时间',
    acked           BIT          NOT NULL DEFAULT 0 COMMENT '是否已回复 ACK',
    PRIMARY KEY (id),
    -- 幂等键：同一设施同一天只保留一条
    UNIQUE KEY uk_barrier_id_date (barrier_id, effective_date),
    INDEX idx_barrier_area_date (area_code, effective_date),
    INDEX idx_barrier_type (barrier_type),
    -- 「按日期取当日全部阻断点」的两个前缀索引都用不上，必须单列
    INDEX idx_barrier_date (effective_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '地图增强信息-交通阻断设施';

-- ============================================================================
-- 11. 媒体资产（事故视频 / 历史录像 / 其他）
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_media_asset (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    vin               VARCHAR(32)  NOT NULL,
    event_id          VARCHAR(64)  NULL COMMENT '关联事故 ID（事故视频填）',
    camera_direction  VARCHAR(4)   NULL COMMENT '摄像头方向 1 前 / 2 右 / 3 后 / 4 左',
    media_type        VARCHAR(8)   NULL COMMENT '文件类型 mp4 等',
    url               VARCHAR(512) NULL COMMENT '对外可访问地址（HLS/下载）',
    storage_path      VARCHAR(512) NULL COMMENT '存储相对路径（本地盘或对象存储 key）',
    start_time        DATETIME(6)  NULL COMMENT '视频起始时间（历史查询按重叠判定）',
    end_time          DATETIME(6)  NULL COMMENT '视频结束时间',
    file_size         BIGINT       NULL COMMENT '文件大小（字节）',
    kind              VARCHAR(16)  NOT NULL COMMENT '类别 ACCIDENT / HISTORY / LIVE',
    status            VARCHAR(16)  NOT NULL COMMENT '状态 PENDING 已登记 / AVAILABLE 可用 / MISSING 文件缺失（与 MediaAsset.Status 枚举一致）',
    uploaded_by       VARCHAR(32)  NULL COMMENT '上传者（VIN 或系统账号）',
    request_msg_id    VARCHAR(64)  NULL COMMENT '对应的媒体请求报文编号（证据链溯源）',
    PRIMARY KEY (id),
    INDEX idx_media_event_dir (event_id, camera_direction),
    INDEX idx_media_vin_time (vin, start_time),
    INDEX idx_media_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '媒体资产（事故视频/历史录像）';

-- ============================================================================
-- 12. MQTT 报文留痕（监管取证 + 排障黑匣子）
--     写入量 = 报文量 × 留痕比例，由 dssad.audit.mode 控制
--     生产建议 sampled(5%) 或 event-only；180 天保留由定时任务清理
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_mqtt_message_log (
    id              BIGINT       NOT NULL COMMENT '主键（应用侧雪花 ID）',
    topic           VARCHAR(255) NOT NULL COMMENT 'Topic 全路径',
    mqtt_type       VARCHAR(32)  NULL COMMENT '报文类型（如 accident、low-base）',
    direction       VARCHAR(8)   NULL COMMENT 'UP 上行 / DOWN 下行',
    sender_id       VARCHAR(64)  NULL COMMENT '发送方标识（VIN 或企业 ID）',
    msg_id          VARCHAR(64)  NULL COMMENT '报文编号（全链路溯源键）',
    ack_code        VARCHAR(4)   NULL COMMENT '回复码 1 确认 / 2 消息有误 / 3 不支持',
    process_status  VARCHAR(16)  NULL COMMENT '处理结果 OK / INVALID / UNSUPPORTED / ERROR / DUPLICATE',
    error_message   VARCHAR(512) NULL COMMENT '失败原因（截断存储）',
    latency_ms      INT          NULL COMMENT '处理耗时 ms',
    size_bytes      INT          NULL COMMENT '报文大小（文档限制 ≤ 256KB）',
    clock_skew_ms   BIGINT       NULL COMMENT '与车端时钟偏差 ms（诊断时间同步问题）',
    received_at     DATETIME(6)  NOT NULL COMMENT '接收时间（留痕主时间轴）',
    payload         MEDIUMTEXT   NULL COMMENT '报文体（≤256KB，超长截断）',
    PRIMARY KEY (id),
    INDEX idx_msg_log_time (received_at),
    INDEX idx_msg_log_vin_time (sender_id, received_at),
    INDEX idx_msg_log_type (mqtt_type, received_at),
    -- 支撑"按 msgId 追溯全链路"
    INDEX idx_msg_log_msg_id (msg_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'MQTT 报文留痕（取证与排障）';

-- ============================================================================
-- 13. 导航路径（groute 报文）
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_navigation_route (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    created_at         DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    vin                VARCHAR(32) NOT NULL,
    enterprise_id      VARCHAR(32) NULL,
    msg_id             VARCHAR(64) NULL,
    reported_at        DATETIME(6) NOT NULL,
    start_latitude     FLOAT(53)   NULL,
    start_longitude    FLOAT(53)   NULL,
    end_latitude       FLOAT(53)   NULL,
    end_longitude      FLOAT(53)   NULL,
    current_latitude   FLOAT(53)   NULL COMMENT '上报时车辆当前位置',
    current_longitude  FLOAT(53)   NULL,
    point_count        INT         NULL COMMENT '路径点数量',
    path_points        MEDIUMTEXT  NULL COMMENT '路径点 JSON [[lat,lng],...]（可达 1MB）',
    PRIMARY KEY (id),
    INDEX idx_route_vin_time (vin, reported_at),
    INDEX idx_route_msg_id (msg_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '导航路径（groute）';

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================================
--  可选增强（按部署规模决定是否启用，启用前请先在预发环境验证）
-- ============================================================================
--
--  A. 轨迹表按天分区 —— 把「删除 180 天前数据」从 DELETE 变成 DROP PARTITION
--
--  ⚠️ 前置条件：MySQL 要求分区键必须属于表的每一个唯一键（含主键）。
--     当前主键是 id（雪花 ID），不含 ts，直接按 ts 分区会报
--     "A PRIMARY KEY must include all columns in the table's partitioning function"。
--     因此启用分区需要先把主键改为复合主键 (ts, id)：
--
--        ALTER TABLE t_vehicle_track_point DROP PRIMARY KEY, ADD PRIMARY KEY (ts, id);
--        ALTER TABLE t_vehicle_track_point
--          PARTITION BY RANGE COLUMNS (ts) (
--            PARTITION p202609 VALUES LESS THAN (1790294400000),
--            PARTITION p202610 VALUES LESS THAN (1792972800000),
--            ...
--            PARTITION pmax    VALUES LESS THAN (MAXVALUE)
--          );
--
--     注意：改成复合主键后，JPA 侧需要用 @IdClass 声明复合主键，
--     属于一次结构性改造。当前版本采用「保留策略定时 DELETE + idx_track_ts」
--     方案（见 TelemetryRetentionService，每天 03:30 分批删除、
--     保留天数由 dssad.retention.track-point-days 控制），
--     在千万级日增量下仍可接受；数据量继续增长到数十亿级时再按上述方案迁移。
--
--  B. 报文留痕表按月分区（同上，需复合主键）。
--
--  C. 状态字段 enum → varchar 的迁移脚本（当需要新增枚举值时）：
--        ALTER TABLE t_accident_event MODIFY media_status VARCHAR(24) NOT NULL;
--        ALTER TABLE t_track_task     MODIFY status       VARCHAR(24) NOT NULL;
--     ▸ 若预计状态机会频繁扩展，建议首次建库就改用 varchar，避免后续迁移；
--       改则需同步把实体上的 @Enumerated 改为 @Convert(converter=...)。
-- ============================================================================
