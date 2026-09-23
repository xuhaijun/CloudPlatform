# 车路通 DSSAD 产品云平台 — 数据库设计说明书

| 项目 | 内容 |
|---|---|
| 文档版本 | v1.0 |
| 编制日期 | 2026-09-23 |
| 适用代码版本 | `dssad-cloud-platform` 1.0.0-SNAPSHOT |
| 上游依据 | 《平台数据对接接口文档 v1.0.0-5》 |
| 数据库 | MySQL 8.0.17+（InnoDB / utf8mb4）|
| 建表脚本 | `cloud-platform-server/db/schema-mysql.sql` |
| 实体定义 | `com.genvict.dssad.cloud.domain.entity`（13 个实体）|
| 仓储定义 | `com.genvict.dssad.cloud.domain.repository`（13 个仓储接口）|
| 验证方式 | 实体 ↔ 生成 DDL ↔ 手写 DDL 三方比对 + 清理逻辑集成测试（见第 7 章）|

> 本文档描述**已实现**的数据库设计，所有字段、索引、默认值均取自代码与建表脚本的当前状态，不含"计划中"的内容。凡是与上游接口文档存在偏差的地方，均在正文标注并在附录 B 登记。

---

## 1 设计概览

### 1.1 存储选型与职责划分

本平台不使用单一存储，而是按**数据形态**分四处落位：

| 存储 | 承担的数据 | 启用条件 | 关键配置 |
|---|---|---|---|
| **MySQL 8** | 档案、事件、故障、任务、媒体元数据、报文留痕（13 张表）| 生产必选 | `spring.datasource` |
| **Redis** | 去重键、限流计数、监管平台令牌、车辆最新状态/位置、缓存 L2 | `dssad.redis.enabled=true` | `spring.data.redis`（关闭时自动降级单机，见 1.4）|
| **本地磁盘 / 对象存储** | 事故取证视频、历史录像等**二进制文件本体** | `dssad.storage.type=local\|minio` | `dssad.storage.local-root` |
| **H2（文件模式）** | 本地开发的整套数据库 | `local` profile（默认） | `jdbc:h2:file:./data/db/dssad`（`MODE=MySQL`）|

三条原则决定了这个划分：

1. **二进制不进库**。视频文件只存**引用**（`t_media_asset.url` + `storage_path`），文件本体落磁盘或对象存储。把 MP4 存进 MySQL 会让备份体积失控、也无法走 CDN。
2. **可重建的不落库**。车辆最新位置/状态在 Redis 里，落库的是**流水**（用于取证与回放）。丢掉 Redis 只影响实时展示，不影响取证链。
3. **审计留痕必须落库**。`t_mqtt_message_log` 是"某时刻收到了什么、回复了什么、耗时多久"的唯一证据，不能放缓存。

### 1.2 表清单与业务域分组

共 13 张表，按业务域分为 5 组：

| 业务域 | 表名 | 中文名 | 增长特征 |
|---|---|---|---|
| **车辆档案** | `t_vehicle` | 车辆主档 | 每车一行 |
| | `t_vehicle_static_param` | 准静态参数变更历史（`inh`）| 每车每天数次 |
| | `t_vehicle_state_snapshot` | 运行状态流水（`low-state`）| 每车 60s 一条 |
| | `t_vehicle_track_point` | 轨迹点（`low-base`）| **每车 1s 一条（最大表）** |
| **事件取证** | `t_accident_event` | 事故事件与取证状态机 | 每起事故一行 |
| | `t_fault_record` | 故障上报主记录 | 每车每天约数条 |
| | `t_fault_item` | 故障明细项 | 主记录 × 平均 2~3 |
| | `t_media_asset` | 媒体资产（事故视频/历史录像/实时流）| 每次取证 1~4 行 |
| **运营调度** | `t_track_task` | 轨迹跟踪任务（开始/结束配对）| 每任务一行 |
| | `t_remote_driving_record` | 远程驾驶接管记录 | 每次接管 1~2 行 |
| | `t_navigation_route` | 导航路径（`groute`）| 每次下发一行 |
| **地图增强** | `t_map_barrier` | 交通阻断设施（推送 + 拉取）| 每设施每天一行 |
| **审计排障** | `t_mqtt_message_log` | MQTT 全量报文留痕 | **报文量 × 采样率（第二大表）** |

### 1.3 实体关系

物理外键**只有一条**（`t_fault_item.fault_record_id`），其余关联均为逻辑外键。这不是疏漏，理由见 2.4 节 —— 流水表上的外键会让批量写入在高并发下相互等待，而 `t_fault_item` 与父记录属于同一报文、同一事务，必须用外键保证不出现孤儿明细。

```
                        ┌───────────────────────────┐
                        │ t_vehicle  (PK: vin)      │
                        │  车辆主档 + 最新准静态参数 │
                        └───────────┬───────────────┘
                                    │ vin（逻辑外键，无约束）
        ┌───────────────┬───────────┼───────────────┬────────────────┐
        │               │           │               │                │
        ▼               ▼           ▼               ▼                ▼
┌───────────────┐┌─────────────┐┌───────────────┐┌──────────────┐┌──────────────────┐
│ static_param  ││ state_snap  ││ track_point   ││ accident     ││ fault_record     │
│ (inh 流水)    ││ (low-state) ││ (low-base)    ││ _event       ││ + navigation     │
│ 雪花ID        ││ 雪花ID      ││ 雪花ID        ││ IDENTITY     ││   _route(IDENTITY)│
└───────────────┘└─────────────┘└───────────────┘└──────┬───────┘└────────┬─────────┘
                                                          │ event_id        │ FK(唯一物理外键)
                                                          │ (业务唯一键)     ▼
                                                          ▼            ┌──────────────┐
                                                    ┌───────────┐     │ fault_item   │
                                                    │media_asset│     │ 故障明细      │
                                                    │ 4 方向视频 │     └──────────────┘
                                                    └───────────┘
        运营侧（与车辆主档逻辑关联）                     审计侧（全局留痕）
   ┌──────────────────┐ ┌────────────────────────┐   ┌────────────────────────┐
   │ t_track_task     │ │ t_remote_driving_record│   │ t_mqtt_message_log     │
   │ 任务生命周期      │ │ 接管生命周期            │   │ 报文全链路（雪花ID）    │
   └──────────────────┘ └────────────────────────┘   └────────────────────────┘
   ┌──────────────────┐
   │ t_map_barrier    │  幂等键 (barrier_id, effective_date)
   │ 交通阻断设施      │
   └──────────────────┘
```

关联键说明（对应上图的连线含义）：

| 关联 | 键 | 基数 | 约束 |
|---|---|---|---|
| 车辆 → 各类流水 | `vin` | 1 : N | 逻辑，无物理外键 |
| 事故事件 → 媒体资产 | `event_id` | 1 : 0..4（按请求方向数）| 逻辑；`t_accident_event.event_id` 有唯一索引防止重复上报 |
| 故障主记录 → 故障明细 | `id` ← `fault_record_id` | 1 : N | **物理外键** |
| 车辆 → 事故/故障 | `vin` | 1 : N | 逻辑；计数冗余在 `t_vehicle.accident_count/fault_count` |
| 任务（开始 → 结束）| `task_id` | 1 : 1（两条报文合成一行）| 唯一索引，保证重发幂等 |
| 接管（发起 → 结束）| `vin` + 最近未结束行 | 1 : 1 | 无协议单号，按「该车最近一条 `closed=false`」配对 |
| 报文留痕 → 业务实体 | `msg_id` | 1 : 0..1 | 索引非唯一（同一 `msg_id` 可能有多条留痕：入站 + 出站 + 重发）|

### 1.4 本地/生产差异（同一套实体，两种数据库）

| 维度 | local（H2 文件库）| prod（MySQL 8）|
|---|---|---|
| DDL 管理 | `ddl-auto: update`（改实体即时生效）| `ddl-auto: validate`（**只校验不改表**，DDL 由 `schema-mysql.sql` 经发布流程执行）|
| 批量插入 | `batch_size: 200` | `batch_size: 500` + 连接串 `rewriteBatchedStatements=true` |
| 连接池 | `maximum-pool-size: 10` | `maximum-pool-size: 20`（虚拟线程下成为唯一并发瓶颈，见 8.3）|
| Redis | 关闭（限流/去重/最新状态退化为进程内实现）| 开启（多实例共享）|
| 日期数组 | `effective_date` 以 `DATE` 落库 | 同左 |

> ⚠️ **`ddl-auto=validate` 的硬约束**：实体与 MySQL 实际表结构不一致时，应用**启动即失败**（不是告警）。因此任何实体改动都必须同步 `schema-mysql.sql`，流程见 7.3 节。

---

## 2 建模约定

### 2.1 主键策略

13 张表使用 **3 种**不同的主键生成方式，按「写入量 + 是否有天然业务键」选择：

| 策略 | 使用的表 | 主键 | 选型理由 |
|---|---|---|---|
| **业务主键** | `t_vehicle` | `vin` | 所有报文都以 VIN 定位车辆，用业务键做主键可省掉一次二级索引查找，且天然防止重复建档（`existsByVin` 只是显式校验）|
| **雪花 ID（应用侧分配）** | `t_vehicle_track_point`、`t_vehicle_state_snapshot`、`t_vehicle_static_param`、`t_mqtt_message_log` | `id BIGINT` | 见下方「为什么不用自增」 |
| **自增 IDENTITY** | 其余 8 张表 | `id BIGINT AUTO_INCREMENT` | 写入量小、无批量插入需求；自增 ID 连续可读，人工排查（`WHERE id = 12345`）最方便 |

**为什么大表必须用应用侧分配的雪花 ID**

Hibernate 在 `GenerationType.IDENTITY` 下**必须逐条 INSERT 并回读自增主键**，这会直接关闭 JDBC 批量插入（`hibernate.jdbc.batch_size` 形同虚设）。轨迹表 1Hz/车、千车规模下每秒 1000 行，逐条往返与批量插入的吞吐差距约 5~10 倍。

雪花 ID 的另一半好处是**趋势递增**：按时间递增写入聚簇索引，页分裂少、脏页少。反例是 UUID 主键 —— 随机写入会让 B+ 树每页都处于"半满"状态，空间放大与写放大都显著。

雪花结构：`1 位符号 | 41 位毫秒时间戳 | 5 位机房 | 5 位机器 | 12 位序列`，起始纪元 2024-01-01（可用约 69 年）。机器位默认取本机 IP 末段（避免手工配置出错），可由 `dssad.instance.worker-id` 覆盖。

**时钟回拨保护**：回拨 ≤ 5ms 时自旋等待，超过 5ms 直接抛异常。宁可写入失败告警，也不生成可能重复的主键 —— 重复主键会导致后续数据覆盖，属于不可恢复的数据事故。

### 2.2 时间字段语义

同一个"时间"在本平台有三类语义，落库形态不同：

| 语义 | 字段 | 类型 | 来源 | 用途 |
|---|---|---|---|---|
| **平台时间** | `created_at` / `updated_at` | `DATETIME(6)` | 应用 `Instant.now()`（`@PrePersist`/`@PreUpdate`）| 数据治理：归档、排障「平台何时处理完」 |
| **业务时间** | `reported_at` / `occurred_at` / `started_at` / `finished_at` | `DATETIME(6)` | 报文 `timestamp` 字段 | 业务口径：事故发生在几时、任务跑了多久 |
| **业务时间（毫秒整数）** | `t_vehicle_track_point.ts` | `BIGINT` | 报文 `timestamp` | 轨迹：范围查询 + 降采样取模 |

**为什么轨迹表的时间用 `BIGINT` 而不是 `DATETIME(6)`**

不是风格不一致，而是三个具体原因：

1. 协议本身就用 13 位毫秒时间戳，`BIGINT` 是**零转换**存储，省掉写入热路径上的时区/精度转换；
2. 降采样查询用的是 `mod(p.ts, :bucketMillis) < 1000`（按时间窗口粗筛），整数取模是天然操作，换成 `DATETIME` 只能改为基于 `reported_at` 的表达式索引或函数计算，无法走索引；
3. 范围条件 `ts BETWEEN ? AND ?` 在 `BIGINT` 上是纯整数比较，比 `DATETIME(6)`（8 字节 + 分数秒编码）更快。

**为什么 `t_fault_item.error_timestamp` 也用 `BIGINT`**

该字段直接来自车端 `errorData[].errorTimestamp`，且**只用于展示，不参与范围查询**（查询走父表的 `reported_at`）。保留协议原始类型可避免"车端时间戳精度/时区理解不同"造成的歧义 —— 排障时可与报文逐字对账。

**时区约定**：所有 `Instant` 以 UTC 落库，展示层按东八区格式化；JDBC 连接串固定 `serverTimezone=Asia/Shanghai`，Jackson `time-zone: Asia/Shanghai`，Hibernate `jdbc.time_zone: Asia/Shanghai`。接口输出统一为 **13 位毫秒时间戳**，`LocalDate` 输出为数组 `[年,月,日]`（前端 `parseTime` 已兼容两种形态）。

### 2.3 命名与类型规范

| 项 | 约定 | 示例 |
|---|---|---|
| 表名 | `t_` 前缀 + 蛇形 | `t_vehicle_track_point` |
| 列名 | 蛇形（Hibernate `CamelCaseToUnderscoresNamingStrategy` 自动转换）| `mediaExpectedCount` → `media_expected_count` |
| 索引名 | `idx_<表语义>_<列语义>`；唯一索引 `uk_` 前缀 | `idx_track_vin_ts`、`uk_accident_event_id` |
| 外键约束 | `FK_<Hibernate 默认名>`（仅 1 处）| `FK_fault_item_record`（手写脚本已命名）|
| 字符串长度 | 按**协议上限**而非"够用"设定 | `topic VARCHAR(255)`、`payload ≤ 262144` |
| 物理量 | `DOUBLE`（MySQL 中即 `FLOAT(53)`）| 经纬度、速度、SOC |
| 百分比/电量 | `DOUBLE` 存原值（`80.5` 表示 80.5%）| `soc`、`endurance` |
| 布尔 | `BIT(1)`；**区分三值语义** | `online` 可为 NULL，`acked`/`closed` NOT NULL DEFAULT 0 |
| 状态枚举 | 见 2.6 | `media_status ENUM(...)` |

**关于 `online` 可为 NULL**：这是刻意的三值设计。`NULL` 表示"从未上报过心跳"（新车刚建档），`0` 表示"上报过但已超时离线"。前端把 `NULL` 与 `0` 一并显示为「离线」是正确的；但**统计在线数必须用 `online = true`**（`countByOnlineTrue()`），若写成 `online != true` 会漏掉 NULL 行。SQL 三值逻辑是这里唯一容易出错的地方，特此记录。

**温度字段的偏移量**：`t_vehicle_state_snapshot.temperature` 落库的是**协议原文**（实际摄氏度 + 100），同时实体提供派生属性 `getTemperatureCelsius()`（不落库，`temperature - 100`）。这样既保留与车端报文逐字对账的能力，又让消费方（前端）无需了解偏移量 —— 接口同时返回两个字段。这是一处刻意的"冗余"，用于从契约层消除"忘了减 100"这类静默错误。

### 2.4 冗余字段（反范式）清单

以下字段都能由其他表推导，但**刻意冗余**，每一处都对应一个具体的 N+1 或聚合查询：

| 表.字段 | 可由什么推导 | 解决的问题 | 一致性维护方式 |
|---|---|---|---|
| `t_vehicle.accident_count` / `fault_count` | `count(t_accident_event)` / `count(t_fault_record)` | 车辆列表页每行都显示事故/故障数，实时 count 会导致 N+1 | 原子 `UPDATE ... SET x = x + 1`（不读改写，避免并发覆盖）|
| `t_vehicle.hw_version` / `ad_version` / `com_type` / `time_sync` | `t_vehicle_static_param` 最新一行 | 列表页展示当前版本，无需对每台车取子表最新行 | `inh` 报文落库时同步覆盖主档 |
| `t_fault_record.max_severity` | `max(t_fault_item.error_severity)` | 故障中心按严重度排序/过滤（"只看 6~7 级"）| 写入时算好 |
| `t_fault_item.vin` | `t_fault_record.vin` | 「按车查明细」「按车聚合故障数」无需 JOIN | 同事务写入 |
| `t_accident_event.media_count` / `media_expected_count` | `count(distinct t_media_asset.camera_direction)` | 取证状态机判定"是否收齐" | 归档时更新 |
| `t_track_task.duration_seconds` | `finished_at - started_at` | 任务列表展示时长 | 结束报文处理时算好 |
| `t_navigation_route.point_count` / `start_*` / `end_*` | `path_points` JSON | 列表展示起点终点与点数，避免解析最大 1MB 的 JSON | 写入时算好 |
| `t_remote_driving_record.duration_seconds` / `closed` | 发起/结束两条报文配对 | 「进行中的远驾」筛选 | 结束报文回填 |

**`media_expected_count` 为什么必须落库而不是写死 4**：监管平台可能只请求 1~2 个方向（例如仅前向）。若按固定 4 判断"已收齐"，该事故会永远停在 `MEDIA_ACCEPTED`，前端一直显示「取证中」。这是个"看起来能跑、实际永远不收敛"的典型缺陷，故单独留字段记录"本次要求几个方向"。

### 2.5 JSON / TEXT 列的判定规则

平台对"数组/嵌套结构"是否拆表，用三个问题判定：

1. **是否需要按元素过滤/排序/聚合？** 需要 → 拆表。`errorData[]` 因此拆成 `t_fault_item`（"近 30 天胎压报警 Top10"这类统计必须在 SQL 里 group by `error_code`）。
2. **消费方式是否"整体读"？** 是 → 留 JSON。导航路径要么整条画出来、要么不用，没有"按单个路径点查询"的场景，拆表只会带来几千倍的存储放大。
3. **大小是否可控且有明确上限？** 必须显式给 `@Column(length = ...)`。

第 3 点是最容易踩的坑，本平台在实体注释中反复强调：

> `@Lob` 若不给 `length`，Hibernate 会为 MySQL 选择最小的文本类型 `tinytext`（**255 字节**）。文档约定单条 MQTT 报文可达 256KB、导航路径可达 1MB，一旦映射成 `tinytext` 就会**静默截断**（不报错、只是数据变短），事故取证视频的关联信息、路径点都会丢失。

当前 JSON/TEXT 列及其映射（已用生成的 DDL 实测确认）：

| 表.列 | 内容 | 声明长度 | MySQL 实际类型 | 说明 |
|---|---|---|---|---|
| `t_vehicle_state_snapshot.tire_pressure` | 胎压数组 JSON（车轮数可变）| 512 | `VARCHAR(512)` | 车轮数少、数值短，字符串足够；顺序有意义（车头从左到右）|
| `t_map_barrier.time_range` | 生效时间段 `[["7:00","9:00"]]` | 1024 | `TEXT` | 每日多时段 |
| `t_accident_event.accident_desc` | 事故描述（车端原文）| 4000 | `TEXT` | 定长描述文本 |
| `t_map_barrier.barrier_polygon` | 多边形顶点 `[[lat,lng],...]` | 65535 | `TEXT` | 首尾闭环；顶点数有限 |
| `t_mqtt_message_log.payload` | 报文体 | 262144 | `MEDIUMTEXT` | 文档上限 256KB |
| `t_navigation_route.path_points` | 全部路径点 | 1048576 | `MEDIUMTEXT` | 跨城任务可达数万点 |

> 空间检索（"车辆是否在某阻断区域内"）**不在 MySQL 内做**。多边形以 JSON 整体存取，真正的空间判断应由 PostGIS / ES 或应用侧（轨迹回放用的是本地米平面投影）完成，`t_map_barrier` 只负责"可信的数据落地"。

### 2.6 枚举落库策略

| 落库形态 | 使用的字段 | 理由 |
|---|---|---|
| **MySQL `ENUM(...)`** | `t_accident_event.media_status`、`t_track_task.status` | 取值集合由平台自身定义、且取值变更意味着**状态机变更**（需要配套发布）。用 DB 层 ENUM 可拦截非法写入 |
| **`VARCHAR(8/16)` + JPA 枚举** | `t_media_asset.kind`/`status`、`t_remote_driving_record.driving_type`、`t_map_barrier.source` | 取值来自协议文档或可能扩展，用字符串避免每次扩展都要 `ALTER TABLE` |
| **`VARCHAR` 存协议码值** | `gnss_status`、`com_type`、`time_sync`、`charge_state`、`date_type`、`error_severity`、`barrier_type` | 协议码值（如 `0~13`）语义由文档定义，平台不改写、只透传；存字符串便于与报文对账 |

**三条硬规则**：

1. JPA 一律 `@Enumerated(EnumType.STRING)`，**禁止 ORDINAL**。`ORDINAL` 存的是枚举序号，日后在枚举中间插入一个值，历史数据的含义会**静默改变**（`REPORTED` 从 0 变成 1），且没有任何报错。
2. 枚举**新增值**时必须走 `ALTER TABLE`（对应 `schema-mysql.sql` 文末的迁移脚本）。若预计状态机会频繁扩展，建议首次建库就改用 `VARCHAR`。
3. `error_severity` 这类"看起来像数字"的字段仍存字符串：它的取值来自协议（`0~7`），且平台不参与数值计算，存字符串可避免"前导零/类型转换"引起的意外。

---

## 3 表结构明细

阅读约定：字段表按**实体声明顺序**排列；"可空"列为 `N` 时表示 `NOT NULL`；"写入频率"按 1000 台车规模估算。

### 3.1 t_vehicle — 车辆主档

每台接入车辆一行。**VIN 直接作为主键**（见 2.1），因此这张表既是车辆档案，也是"所有以 VIN 关联的查询"的入口。

主档里冗余了准静态参数的**最新值**（`hw_version` 等 4 列），使车辆列表页只需查一张表。

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `vin` | VARCHAR(32) | N | 车辆 VIN，**主键** |
| `created_at` / `updated_at` | DATETIME(6) | N | 平台建档/最后更新时间 |
| `accident_count` | BIGINT | N，默认 0 | 累计事故数（冗余计数）|
| `fault_count` | BIGINT | N，默认 0 | 累计故障上报数（冗余计数）|
| `online` | BIT(1) | Y | 是否在线；**NULL = 从未上报过心跳** |
| `vehicle_type` | VARCHAR(16) | Y | `low_speed` / `high_speed`（决定车云 Topic 段）|
| `plate_no` | VARCHAR(16) | Y | 车牌号（企业自有信息，非协议字段）|
| `model` | VARCHAR(64) | Y | 车型 |
| `device_sn` | VARCHAR(64) | Y | 车载终端/DSSAD 设备序列号 |
| `hw_version` | VARCHAR(64) | Y | 自动驾驶硬件版本（来自 `inh`）|
| `ad_version` | VARCHAR(64) | Y | 自动驾驶系统软件版本（来自 `inh`）|
| `com_type` | VARCHAR(8) | Y | 蜂窝通讯类型 `0`未知/`1`4G/`2`5G/`3`5GA/`4`6G |
| `time_sync` | VARCHAR(8) | Y | 时间同步方式 `0~5` |
| `enterprise_id` | VARCHAR(32) | Y | 所属企业 ID |
| `last_inh_at` | DATETIME(6) | Y | 最后收到 `inh` 的时间 |
| `last_base_at` | DATETIME(6) | Y | 最后收到 `low-base` 的时间（**在线判定的基准**）|
| `last_state_at` | DATETIME(6) | Y | 最后收到 `low-state` 的时间 |
| `last_online_at` | DATETIME(6) | Y | 最后上线时间 |
| `version` | BIGINT | Y | JPA 乐观锁版本号 |

索引：

| 索引 | 列 | 支撑的查询 |
|---|---|---|
| PRIMARY | `vin` | 所有按 VIN 取档案；`existsByVin` |
| `idx_vehicle_enterprise` | `enterprise_id` | `findByEnterpriseId`（多企业部署时按企业隔离）|
| `idx_vehicle_online` | `online, last_base_at` | 在线列表：过滤 `online=true` + 按心跳排序；`markOfflineBefore`（心跳超时批量置离线）|

**为什么用 `@Version` 乐观锁**：同一台车可能被"报文处理线程（更新心跳/计数）"与"人工编辑（改车牌、车型）"同时更新。悲观锁会让高频的报文写入排队，乐观锁只在真正冲突时重试，更合适。

**写入模式**：`touchOnline` / `markOfflineBefore` / `increaseAccidentCount` / `increaseFaultCount` 全部是 `@Modifying` 的**单条 UPDATE**，不做"查实体 → 改字段 → 保存"。原因是 `low-base` 1Hz 进这条路径，若每次都加载整行再全列写回，会产生大量无意义的 SELECT 与全列 UPDATE。

### 3.2 t_vehicle_static_param — 准静态参数变更历史（`inh`）

车端在**每次 MQTT 连接/重连完成后**上报一次 `inh`，因此是低频表（每车每天几次），保留全量以追溯"软件版本何时升级"。

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | BIGINT | N | **主键（应用侧雪花 ID）** |
| `created_at` / `updated_at` | DATETIME(6) | N | 平台时间 |
| `vin` | VARCHAR(32) | N | 车辆 VIN |
| `reported_at` | DATETIME(6) | N | 车端上报时间（业务时间）|
| `msg_id` | VARCHAR(64) | Y | 报文编号（用于与 ACK 配对去重）|
| `hw_version` | VARCHAR(64) | Y | 硬件版本 |
| `ad_version` | VARCHAR(64) | Y | 软件版本 |
| `com_type` | VARCHAR(8) | Y | 通讯类型 |
| `time_sync` | VARCHAR(8) | Y | 时间同步方式 |

索引：`PRIMARY(id)`、`idx_inh_vin_time(vin, reported_at)`（查某车版本演进）、`idx_inh_msg_id(msg_id)`（按报文追溯）。

**为什么这张"低频表"也用雪花 ID**：不是为了批量插入（每天每车只有几条），而是为了**写入路径一致**——`inh` 与 `low-state`/`low-base` 在同一个消费线程/同一个刷盘批次里处理，若三者主键策略不同，批处理代码就要分叉。统一为 `ASSIGNED` 后，`saveAll` 的语义对三张表完全一致。

### 3.3 t_vehicle_state_snapshot — 运行状态流水（`low-state`）

文档 5.1.4.3.1，约 60s 一条。**按流水落库**（保留全量供取证），同时把最新值写入 Redis（`vehicle:state:{vin}`）供列表页/大屏实时读取 —— 避免列表页对每台车都执行 `ORDER BY reported_at DESC LIMIT 1`。

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | BIGINT | N | **主键（雪花 ID）** |
| `vin` | VARCHAR(32) | N | 车辆 VIN |
| `reported_at` | DATETIME(6) | N | 上报时间 |
| `msg_id` | VARCHAR(64) | Y | 车端消息编号 |
| `gnss_status` | VARCHAR(8) | Y | 定位状态 `0~13`（`4` 固定解、`13` RTK+IMU+视觉组合有效）|
| `vehicle_total_weight` | DOUBLE | Y | 实时总质量 kg，0 表示未知 |
| `tire_pressure` | VARCHAR(512) | Y | 胎压数组 JSON，单位 kPa，**顺序有意义**（车头从左到右）|
| `endurance` | DOUBLE | Y | 预计续航 km |
| `mileage` | DOUBLE | Y | 里程表读数 km |
| `soc` | DOUBLE | Y | 电池剩余电量 % |
| `battery_capacity` | DOUBLE | Y | 电池容量（度）|
| `temperature` | INT | Y | **协议原文**：实际摄氏度 = 本值 − 100 |
| `charge_state` | VARCHAR(8) | Y | `0` 缺省 / `1` 未充电 / `3` 正在充电 / `4` 充电故障 |

索引：`PRIMARY(id)`、`idx_state_vin_time(vin, reported_at)`（状态历史分页 `findByVinAndReportedAtBetween...`）。

**不继承 `BaseEntity`**：本表是追加型流水，业务时间即 `reported_at`，再加 `created_at`/`updated_at` 每天每车多写 2880 次无用字段。同类的还有 `t_vehicle_track_point` 与 `t_mqtt_message_log`。

**胎压为什么不拆表**：查询场景是"整组展示"（前端把胎压画成车轮示意图），没有"按单个胎压值过滤"的需求。拆表只会让一行变四行且引入顺序维护成本。

### 3.4 t_vehicle_track_point — 轨迹点（`low-base`）★ 全库写入量最大的表

文档 5.1.4.3.2，**1Hz/车**。1000 台车 ≈ **8640 万行/天**，是容量与性能设计的主要矛盾点。

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | BIGINT | N | **主键（雪花 ID，按时间递增）** |
| `vin` | VARCHAR(32) | N | 车辆 VIN |
| `ts` | BIGINT | N | 采样时间（13 位毫秒，**业务时间**）|
| `longitude` / `latitude` | DOUBLE | Y | 经纬度（GCJ-02）|
| `altitude` | DOUBLE | Y | 高程 m |
| `speed` | DOUBLE | Y | 速度 km/h |
| `acc_speed` | DOUBLE | Y | 加速度（单位 `0.01 m/s²`）|
| `driving_mode` | INT | Y | `0` 自动驾驶 / `1` 人工驾驶 / `2` 远程接管 |
| `heading` | DOUBLE | Y | 航向角（正北 0°，顺时针，精度 0.01°）|

索引：

| 索引 | 列 | 支撑的查询 |
|---|---|---|
| PRIMARY | `id` | 批量插入（聚簇）|
| `idx_track_vin_ts` | `vin, ts` | **轨迹回放的生命线**：`findTrack`、`findTrackBucketed`、`countByVinAndTsBetween`、`findFirstByVinAndTsLessThanEqualOrderByTsDesc` |
| `idx_track_ts` | `ts` | **保留策略清理**：`deleteBatchBefore`（条件只有时间，无 VIN，只能用单列索引）|

**四条硬约束**（任何一条被破坏都会导致数据库不可用）：

1. **极窄行**：只有 9 个列、全部定长数值，单行约 178 字节（含索引）。不含任何长字符串 —— 这是"单表一年能存下几十亿行"的前提。
2. **查询必须带时间范围 + 条数上限**：仓储层用方法签名强制（`findTrack(vin, fromTs, toTs, limit)`），服务层再做抽稀（`TrackSimplifyService`：先判总量，超阈值则按时间窗口降采样 + Douglas-Peucker 抽稀，容差 `dssad.api.track-simplify-tolerance=8.0` 米）。
3. **写入必须走批量**：应用侧分配主键 + `hibernate.jdbc.batch_size=500` + `rewriteBatchedStatements=true` + 每秒刷盘一次（`TelemetryService`，单批上限 5000）。缓冲上限 100000，超限**丢弃并计数告警**（宁可丢采样点也不能 OOM）。
4. **必须有保留策略**：见第 6 章。没有清理时单表数周内到十亿行。

**降采样为什么在 SQL 里做**：`findTrackBucketed` 用 `mod(p.ts, :bucketMillis) < 1000` 在数据库侧先粗筛。若先把 8.6 万点/天全部拉回应用再抽稀，一次跨天查询就会在网络与堆内存上压垮服务 —— 该查询的存在意义就是"让数据库只返回万级以内的行"。

**为什么不按天分区（当前版本）**：MySQL 要求分区键必须属于每一个唯一键。本表主键是 `id`（雪花），不含 `ts`，按 `ts` 分区会直接报错；若改成复合主键 `(ts, id)`，JPA 侧需要 `@IdClass`，属于结构性改造。当前选择"保留策略 + `idx_track_ts`"，迁移方案已写在 `schema-mysql.sql` 文末的「可选增强 A」。

### 3.5 t_accident_event — 事故事件与取证状态机

文档 5.1.4.4.1。承载完整业务闭环：**车端上报事故 → 平台落库（`REPORTED`）→ 下发媒体请求（`MEDIA_REQUESTED`）→ 车端受理（`MEDIA_ACCEPTED`）→ 视频经 HTTP 上传归档（`MEDIA_ARCHIVED`）**，失败/超时进入 `MEDIA_FAILED`。

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | BIGINT | N | 主键（**IDENTITY 自增**）|
| `created_at` / `updated_at` | DATETIME(6) | N | 平台时间 |
| `event_id` | VARCHAR(64) | N | **车端生成的事故 ID，全局唯一** |
| `vin` | VARCHAR(32) | N | 车辆 VIN |
| `enterprise_id` | VARCHAR(32) | Y | 企业 ID |
| `msg_id` | VARCHAR(64) | Y | 首个事故上报报文编号 |
| `occurred_at` | DATETIME(6) | N | 事发时间（报文 `timestamp`）|
| `accident_desc` | TEXT | Y | 事故描述（车端原文）|
| `latitude` / `longitude` | DOUBLE | Y | 事发位置（报文 `currentPoint[0]` / `[1]`）|
| `media_status` | ENUM(...) | N | 取证状态机，5 个取值（见下）|
| `media_requested_at` | DATETIME(6) | Y | 下发媒体请求的时间 |
| `media_count` | INT | N，默认 0 | 已归档的**文件数** |
| `media_expected_count` | INT | N，默认 0 | 本次请求要求的**方向数**（收齐判定依据，见 2.4）|
| `media_request_msg_id` | VARCHAR(64) | Y | 媒体请求报文编号（与车端应答/上传配对）|

`media_status` 的 5 个枚举值：`REPORTED`、`MEDIA_REQUESTED`、`MEDIA_ACCEPTED`、`MEDIA_ARCHIVED`、`MEDIA_FAILED`。

索引：

| 索引 | 列 | 支撑的查询 |
|---|---|---|
| PRIMARY | `id` | 详情页 |
| `uk_accident_event_id` **UNIQUE** | `event_id` | **幂等**：同一事故重复上报只落一条；`findByEventId` / `existsByEventId` |
| `idx_accident_vin_time` | `vin, occurred_at` | 某车的历次事故 |
| `idx_accident_status` | `media_status, occurred_at` | **待取证看板**（按状态 + 时间筛选）、`countByMediaStatus`、`findUnarchivedBefore` |

**`event_id` 唯一索引是幂等的基石**：车端可能因未收到 ACK 而重发事故上报，若按自增主键落库就会产生两条"同一事故"，取证进度、媒体计数全部翻倍。用业务唯一键 + 唯一索引兜底，重发即幂等。

**`media_request_msg_id` 的配对作用**：车端回复 `accident-media-resp` 时只带 `ackMsgId`，平台据此反查是哪个事件（`findFirstByMediaRequestMsgId`）。这是"事故 → 请求 → 应答 → 上传"整条链路的连接点，因此建了索引。

### 3.6 t_fault_record / t_fault_item — 故障上报（主表 + 明细）

文档 5.1.4.4.2。一次 `error-report` 报文可携带 N 条故障（`errorData[]`），因此拆成主表 + 明细表：既能按"上报事件"回溯，又能按 `error_code` 做聚合统计（如"近 30 天电池故障 Top10 车辆"），避免把明细塞进 JSON 导致无法索引。

**3.6.1 t_fault_record（主记录）**

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | BIGINT | N | 主键（IDENTITY）|
| `created_at` / `updated_at` | DATETIME(6) | N | 平台时间 |
| `vin` | VARCHAR(32) | N | 车辆 VIN |
| `msg_id` | VARCHAR(64) | Y | 上报消息编号 |
| `reported_at` | DATETIME(6) | N | 上报时间 |
| `error_num` | INT | Y | 故障个数（报文 `errorNum`）|
| `max_severity` | INT | Y | **本次上报中最严重的等级 0~7**（冗余，见 2.4）|
| `latitude` / `longitude` | DOUBLE | Y | 上报位置 |

索引：`idx_fault_vin_time(vin, reported_at)`、`idx_fault_severity(max_severity, reported_at)`、**`idx_fault_time(reported_at)`**（近 N 天聚合，本次核验新增，见 7.2）。

**3.6.2 t_fault_item（明细）**

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | BIGINT | N | 主键（IDENTITY）|
| `created_at` / `updated_at` | DATETIME(6) | N | 平台时间 |
| `fault_record_id` | BIGINT | N | **物理外键** → `t_fault_record.id` |
| `vin` | VARCHAR(32) | N | 车辆 VIN（冗余，避免 JOIN）|
| `error_code` | VARCHAR(16) | N | 故障码（附录 A.1 共 6 大类 28 个码，另加 1 个复合码）|
| `error_severity` | VARCHAR(8) | Y | 严重等级 `0~7` |
| `error_reason` | VARCHAR(255) | Y | 故障原因（车端原文）|
| `error_timestamp` | BIGINT | Y | 车端故障时间戳（毫秒）|
| `msg_count` | INT | Y | 该故障类别的累计上报次数（车端自计量）|

索引：`idx_fault_item_code(error_code, error_timestamp)`（故障码 Top N 聚合）、`idx_fault_item_record(fault_record_id)`（详情页取明细 + 外键校验）。

**两个"看不到的错误"在这里被显式挡住**（都写在实体注释里，属于踩过的坑）：

1. **列表接口不能序列化明细**。`FaultRecord.items` 标了 `@JsonIgnore`。原因：列表查询不带明细，而 `spring.jpa.open-in-view=false` 让 Jackson 序列化发生在事务之外，懒加载集合会抛 `LazyInitializationException`，把整个列表接口变成 9999；即使能加载，一页 20 条 × 每条若干明细也会显著放大响应体。明细改由详情接口单独返回，与事故详情"主记录 + 关联清单"的结构保持一致。该约束由 `PlatformEndToEndTest.faultItemsArePersistedAndTranslated` 守护。
2. **明细不能反向序列化父记录**。`FaultItem.faultRecord` 同样标 `@JsonIgnore` —— 否则 Jackson 会沿 `item → record → items → item` 成环，轻则响应体膨胀数倍，重则栈溢出。

**`error_severity` 存字符串而非数字**：取值来自协议 `0~7`，平台不做数值计算，存字符串可避免类型转换与前导零问题；`max_severity` 是**平台自己算出来的整数**，因此用 INT 便于排序比较。

### 3.7 t_media_asset — 媒体资产

三类场景共表，用 `kind` 区分：`ACCIDENT`（事故取证四方向视频）、`HISTORY`（历史行车记录）、`LIVE`（实时拉流地址，不落文件）。

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | BIGINT | N | 主键（IDENTITY）|
| `created_at` / `updated_at` | DATETIME(6) | N | 平台时间 |
| `vin` | VARCHAR(32) | N | 车辆 VIN |
| `event_id` | VARCHAR(64) | Y | 关联事故 ID（历史/实时视频为空）|
| `camera_direction` | VARCHAR(4) | Y | `1` 前 / `2` 右 / `3` 后 / `4` 左 |
| `media_type` | VARCHAR(8) | Y | `m3u8` / `flv` / `mp4` |
| `url` | VARCHAR(512) | Y | 对外可访问地址（拉流或下载）|
| `storage_path` | VARCHAR(512) | Y | 存储相对路径（本地相对路径或对象存储 key）|
| `start_time` / `end_time` | DATETIME(6) | Y | 视频起止时间（历史查询按重叠判定）|
| `file_size` | BIGINT | Y | 文件大小（字节）|
| `kind` | VARCHAR(16) | N | `ACCIDENT` / `HISTORY` / `LIVE` |
| `status` | VARCHAR(16) | N | `PENDING` / `AVAILABLE` / `MISSING` |
| `uploaded_by` | VARCHAR(32) | Y | 上传方（车端填 VIN，平台构建填 `SYSTEM`）|
| `request_msg_id` | VARCHAR(64) | Y | 媒体请求报文编号（证据链溯源）|

索引：`idx_media_event_dir(event_id, camera_direction)`、`idx_media_vin_time(vin, start_time)`、`idx_media_status(status)`。

**`url` 与 `storage_path` 为什么分开**：对外地址会随网关/域名变化（开发环境 `127.0.0.1:8080`、生产走 CDN 域名），不应与物理存储路径耦合。迁移存储或换域名时只需重算 `url`，`storage_path` 保持稳定。

**历史视频查询用"重叠"而非"包含"**：`findByVinAndCameraDirectionAndStartTimeLessThanEqualAndEndTimeGreaterThanEqual...` 的语义是 `start_time <= 查询结束 AND end_time >= 查询开始`。因为点播场景用户给的是 `00:00~24:00`，而录像文件是 `00:00~01:00` 这种切片，用"完全落在区间内"会把所有切片都过滤掉。

**收齐判定用"不同方向数"而非"文件数"**：`countDistinctDirectionByEventId` 统计 `distinct camera_direction`。车端可能因重发对同一方向上传多次，若按文件数统计，2 个方向各传 2 次就会被误判为"已收齐 4 个方向"。

### 3.8 t_track_task — 轨迹跟踪任务

文档 6.1.4.2.2。`taskAction=1`（开始）插入新记录，`taskAction=2`（结束）按 `task_id` 更新同一条 —— **两条报文合成一行生命周期记录**。

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | BIGINT | N | 主键（IDENTITY）|
| `created_at` / `updated_at` | DATETIME(6) | N | 平台时间 |
| `task_id` | VARCHAR(64) | N | 任务 ID（企业云端生成，如 `TASK_20251105_001`）|
| `enterprise_id` | VARCHAR(32) | Y | 企业 ID |
| `vin` | VARCHAR(32) | N | 车辆 VIN |
| `start_position` / `end_position` | VARCHAR(255) | Y | 起终点描述 |
| `start_latitude` / `start_longitude` | DOUBLE | Y | 起点坐标 |
| `end_latitude` / `end_longitude` | DOUBLE | Y | 终点坐标 |
| `started_at` / `finished_at` | DATETIME(6) | Y | 任务起止时间 |
| `status` | ENUM(...) | N | `RUNNING` / `FINISHED` / **`ORPHAN_FINISH`** |
| `last_msg_id` | VARCHAR(64) | Y | 最后一次关联的报文编号 |
| `duration_seconds` | BIGINT | Y | 任务时长（秒，结束时算好，见 2.4）|

索引：`uk_task_id` **UNIQUE**（配对键，保证重发幂等）、`idx_task_vin_status(vin, status)`、`idx_task_started_at(started_at)`、**`idx_task_status_time(status, started_at)`**（运营页按状态视图，本次核验新增）。

**`ORPHAN_FINISH` 是刻意保留的"坏数据"标记**：只收到结束报文、找不到开始记录时，平台**不丢弃**、而是落一条 `ORPHAN_FINISH`。丢弃会让"任务少了一条"这种数据缺失从此无法被发现；保留并标红则让问题浮出水面（前端运营页对 `ORPHAN_FINISH` 单独红色告警）。这类"宁可留痕也不静默丢弃"的取舍在本平台是一致的。

### 3.9 t_remote_driving_record — 远程驾驶接管记录

文档 6.1.4.2.1。记录"发起接管（`type=1`）→ 结束接管（`type=2`）"的完整过程。发起时必传的驾驶员姓名、驾驶证号、台架号属于**安全监管取证要素**，因此单独建表并全量留存，**不做脱敏覆盖**（展示层按权限打码，库里保留原文 —— 监管核查时需要原文）。

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | BIGINT | N | 主键（IDENTITY）|
| `created_at` / `updated_at` | DATETIME(6) | N | 平台时间 |
| `vin` | VARCHAR(32) | N | 车辆 VIN |
| `enterprise_id` | VARCHAR(32) | Y | 企业 ID |
| `driving_type` | VARCHAR(8) | N | `1` 发起接管 / `2` 结束接管 |
| `driver` | VARCHAR(64) | Y | 驾驶员姓名（发起时必传）|
| `driver_card` | VARCHAR(64) | Y | 驾驶证号（发起时必传）|
| `remote_cockpit_no` | VARCHAR(64) | Y | 台架号 / 远驾舱编号（发起时必传）|
| `occurred_at` | DATETIME(6) | N | 接管事件时间 |
| `msg_id` | VARCHAR(64) | Y | 报文编号 |
| `duration_seconds` | BIGINT | Y | 接管时长（结束时报文回填）|
| `closed` | BIT(1) | N，默认 0 | 是否已结束（列表默认只看进行中）|

索引：`idx_remote_vin_time(vin, occurred_at)`、`idx_remote_type(driving_type, occurred_at)`。

**配对键的妥协**：协议**没有**提供"接管单号"，因此平台以"该车最近一条 `closed=false` 的发起记录"回填结束时间（`findFirstByVinAndClosedFalseOrderByOccurredAtDesc`）。这个妥协有一个已知边界：若同一车存在两次未关闭的接管发起，结束报文只会回填最近那条，另一条会永久 `closed=false`。应对方式是在前端列出"进行中的接管"并暴露"缺驾驶员/已结束无时长"异常列（`RemoteDrivingView`），让异常可见而非静默。**这是协议侧的缺口，已登记至接口文档附录 C。**

### 3.10 t_navigation_route — 导航路径（`groute`）

文档 6.1.4.2.3。报文携带"起点 + 途经点 + 终点 + 全部路径点"，点数可达数千。

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | BIGINT | N | 主键（IDENTITY）|
| `created_at` / `updated_at` | DATETIME(6) | N | 平台时间 |
| `vin` | VARCHAR(32) | N | 车辆 VIN |
| `enterprise_id` | VARCHAR(32) | Y | 企业 ID |
| `msg_id` | VARCHAR(64) | Y | 报文编号 |
| `reported_at` | DATETIME(6) | N | 上报时间 |
| `path_points` | MEDIUMTEXT | Y | **全部路径点 JSON `[[lat,lng],...]`**，首元素起点、末元素终点（可达 1MB）|
| `point_count` | INT | Y | 路径点数量（冗余）|
| `start_latitude` / `start_longitude` | DOUBLE | Y | 起点（冗余）|
| `end_latitude` / `end_longitude` | DOUBLE | Y | 终点（冗余）|
| `current_latitude` / `current_longitude` | DOUBLE | Y | 上报时车辆当前位置 |

索引：`idx_route_vin_time(vin, reported_at)`、`idx_route_msg_id(msg_id)`。

**为什么不拆"路径点明细表"**：导航路径是**一次性整体消费**的数据（要么整条画出来，要么不用），没有按单个路径点过滤/聚合的场景。一次跨城任务数千点，拆表会带来几千倍的存储放大与写入开销。列表页需要的信息（起终点、点数）已冗余成独立列，避免为了展示几个字段去解析 1MB JSON。

### 3.11 t_map_barrier — 交通阻断设施 / 地图增强信息

两个来源统一落本表，用 `source` 区分：`PUSH`（监管平台经 MQTT `mapehn/down` 主动推送）、`PULL`（平台每天通过 HTTP `/event/mapehn` 拉取"今日"阻断点，`@Scheduled(cron = "0 5 0 * * ?")`）。

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | BIGINT | N | 主键（IDENTITY）|
| `created_at` / `updated_at` | DATETIME(6) | N | 平台时间 |
| `enterprise_id` | VARCHAR(32) | Y | 企业 ID |
| `area_code` | VARCHAR(64) | Y | 区域编码（拉取时可按区域过滤）|
| `barrier_id` | VARCHAR(64) | N | 交通阻断编号（平台分配）|
| `barrier_type` | VARCHAR(8) | N | **`20` 禁行区域 / `21` 限速区域** |
| `barrier_name` | VARCHAR(128) | Y | 名称，如「学校区域」|
| `barrier_polygon` | TEXT | Y | 多边形顶点 JSON `[[lat,lng],...]`，首尾同点闭环 |
| `date_type` | VARCHAR(8) | Y | `1` 工作日 / `2` 节假日 / `3` 每日 / `4` 周六周日 |
| `time_range` | TEXT | Y | 生效时间段 JSON `[["7:00","9:00"],...]` |
| `speed_limit` | DOUBLE | Y | 限速值（`barrierType=21` 时有效）|
| `effective_date` | DATE | Y | 生效日期（**幂等键的组成之一**）|
| `source` | VARCHAR(8) | Y | `PUSH` / `PULL` |
| `msg_id` | VARCHAR(64) | Y | 平台侧消息编号（推送时）|
| `received_at` | DATETIME(6) | Y | 平台侧下发时间 |
| `acked` | BIT(1) | N，默认 0 | 是否已回复 ACK（推送场景下必须回 `mapehn-resp`）|

索引：`uk_barrier_id_date` **UNIQUE(barrier_id, effective_date)**、`idx_barrier_area_date(area_code, effective_date)`、`idx_barrier_type(barrier_type)`、**`idx_barrier_date(effective_date)`**（本次核验新增，见 7.2）。

**幂等键 `(barrier_id, effective_date)`**：同一阻断设施在同一天可能被推送多次（平台重发）又被 HTTP 拉取一次。以"设施 + 日期"为幂等维度，重复到达只更新不新增。选日期而不选"推送批次"，是因为业务语义就是"今天该设施是否阻断"。

**`acked` 字段用于对账**：推送场景下平台有回复 `mapehn-resp` 的义务，`acked` 让"哪些推送没回复"变成一个可查询的条件，而不是靠翻日志。

### 3.12 t_mqtt_message_log — MQTT 报文留痕 ★ 第二大表

**为什么全量留存**：本系统服务于《成都市智能网联汽车道路测试、示范应用与示范运营管理规范实施细则》等监管要求。一旦发生事故或监管核查，必须能自证"某时刻收到了什么、回复了什么、耗时多久"，因此每个入站/出站报文都登记一条。

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | BIGINT | N | **主键（雪花 ID）** |
| `topic` | VARCHAR(255) | N | 原始 Topic 全路径 |
| `direction` | VARCHAR(8) | Y | `UP` / `DOWN` |
| `mqtt_type` | VARCHAR(32) | Y | 报文类型字面量（如 `accident`、`low-base`）|
| `sender_id` | VARCHAR(64) | Y | 发送方标识（车端为 VIN，企业云端为 `enterpriseId`）|
| `msg_id` | VARCHAR(64) | Y | 报文编号（**全链路溯源键**）|
| `payload` | MEDIUMTEXT | Y | 报文体（≤256KB，超长截断）|
| `size_bytes` | INT | Y | 报文字节数 |
| `received_at` | DATETIME(6) | N | 平台接收/发送时间（**留痕主时间轴**）|
| `process_status` | VARCHAR(16) | Y | `OK` / `INVALID` / `UNSUPPORTED` / `ERROR` / `DUPLICATE` |
| `ack_code` | VARCHAR(4) | Y | 回复的 `res` 值（`1` 确认 / `2` 消息有误 / `3` 不支持）|
| `error_message` | VARCHAR(512) | Y | 失败原因摘要（截断存储）|
| `latency_ms` | INT | Y | 处理耗时 ms（定位慢 Handler 与端到端时延）|
| `clock_skew_ms` | BIGINT | Y | 报文内时间戳与平台接收时间的差值（诊断车端时钟同步问题）|

索引：

| 索引 | 列 | 支撑的查询 |
|---|---|---|
| `idx_msg_log_time` | `received_at` | 时间范围检索 + **保留策略清理** `deleteBefore` |
| `idx_msg_log_vin_time` | `sender_id, received_at` | 按车筛选留痕 |
| `idx_msg_log_type` | `mqtt_type, received_at` | 按报文类型筛选（排障常用）|
| `idx_msg_log_msg_id` | `msg_id` | **按 msgId 全链路追溯**（前端「追溯」弹窗）|

**`clock_skew_ms` 是一个"提前埋好的诊断字段"**：车端时钟偏差会导致轨迹时间错乱、事件时间与视频时间对不上，而这类问题在日志里极难定位。把偏差**算在写入路径上**（一次减法），就把它变成了一个 `SELECT ... WHERE ABS(clock_skew_ms) > 60000` 就能回答的问题。

**写入策略**：应用侧雪花主键 + JDBC 批量插入；写入量由 `dssad.audit.mode` 三档控制 —— `full` 全量 / `sampled` 按比例采样 / `event-only` 仅事件类报文（后两档的意义见第 5 章容量估算）。

---

## 4 索引设计与查询对照

13 张表共 **47 个索引**（含主键，不含 1 个外键约束）。下表把**每个索引与它实际支撑的仓储方法**一一对应 —— 索引不是为了"看起来完整"，每一处都能指出是谁在用它。

### 4.1 索引清单与用途对照

| 表 | 索引 | 列 | 支撑的查询（仓储方法）| 类型 |
|---|---|---|---|---|
| `t_vehicle` | PRIMARY | `vin` | 全部按 VIN 查档案 | 聚簇 |
| | `idx_vehicle_enterprise` | `enterprise_id` | `findByEnterpriseId` | 普 |
| | `idx_vehicle_online` | `online, last_base_at` | `countByOnlineTrue` / `markOfflineBefore`（前缀 `online`）+ 在线列表排序 | 普 |
| `t_vehicle_static_param` | `idx_inh_vin_time` | `vin, reported_at` | `findByVinOrderByReportedAtDesc` / `findFirstByVinOrderByReportedAtDesc` | 普 |
| | `idx_inh_msg_id` | `msg_id` | 按报文追溯 | 普 |
| `t_vehicle_state_snapshot` | `idx_state_vin_time` | `vin, reported_at` | `findByVinAndReportedAtBetweenOrderByReportedAtDesc` / `findFirstByVinOrderByReportedAtDesc` | 普 |
| `t_vehicle_track_point` | `idx_track_vin_ts` | `vin, ts` | `findTrack` / `findTrackBucketed` / `countByVinAndTsBetween` / `findFirstByVinAndTsLessThanEqualOrderByTsDesc` | 普 |
| | `idx_track_ts` | `ts` | `deleteBatchBefore`（保留策略）| 普 |
| `t_accident_event` | `uk_accident_event_id` | `event_id` | `findByEventId` / `existsByEventId`（幂等）| **唯一** |
| | `idx_accident_vin_time` | `vin, occurred_at` | `findByVinOrderByOccurredAtDesc` / `search`（按车）| 普 |
| | `idx_accident_status` | `media_status, occurred_at` | `countByMediaStatus` / `findUnarchivedBefore` / `search`（待取证看板）| 普 |
| `t_fault_record` | `idx_fault_vin_time` | `vin, reported_at` | `findByVinOrderByReportedAtDesc` / `search`（按车）| 普 |
| | `idx_fault_severity` | `max_severity, reported_at` | `findByMaxSeverityGreaterThanEqualOrderByReportedAtDesc` | 普 |
| | `idx_fault_time` | `reported_at` | `countByReportedAtAfter` / `countByMaxSeverityGreaterThanEqualAndReportedAtAfter` / `topErrorCodes`、`topFaultVehicles` 的父表 JOIN 过滤 | 普（**本次新增**）|
| `t_fault_item` | `idx_fault_item_code` | `error_code, error_timestamp` | `topErrorCodes`（库内 group by）/ `countByErrorCodeAndErrorTimestampAfter` | 普 |
| | `idx_fault_item_record` | `fault_record_id` | 详情取明细 `findWithItems`；同时充当外键索引 | 普 |
| `t_media_asset` | `idx_media_event_dir` | `event_id, camera_direction` | `findByEventId` / `findFirstByEventIdAndCameraDirection` / `countDistinctDirectionByEventId` | 普 |
| | `idx_media_vin_time` | `vin, start_time` | `findByVinOrderByStartTimeDesc` / 历史视频重叠查询 | 普 |
| | `idx_media_status` | `status` | 归档状态巡检（找 `PENDING`/`MISSING`）| 普 |
| `t_track_task` | `uk_task_id` | `task_id` | `findByTaskId`（开始/结束配对，幂等）| **唯一** |
| | `idx_task_vin_status` | `vin, status` | `findByVinOrderByStartedAtDesc` | 普 |
| | `idx_task_started_at` | `started_at` | `findByStartedAtBetweenOrderByStartedAtDesc` | 普 |
| | `idx_task_status_time` | `status, started_at` | `findByStatusOrderByStartedAtDesc` / `countByStatus` | 普（**本次新增**）|
| `t_remote_driving_record` | `idx_remote_vin_time` | `vin, occurred_at` | `findByVinOrderByOccurredAtDesc` / `findFirstByVinAndClosedFalseOrderByOccurredAtDesc` / `search` | 普 |
| | `idx_remote_type` | `driving_type, occurred_at` | 按发起/结束统计 `countByDrivingTypeAndOccurredAtAfter` | 普 |
| `t_navigation_route` | `idx_route_vin_time` | `vin, reported_at` | `findByVinOrderByReportedAtDesc` / `findFirstByVinOrderByReportedAtDesc` | 普 |
| | `idx_route_msg_id` | `msg_id` | 按报文追溯 | 普 |
| `t_map_barrier` | `uk_barrier_id_date` | `barrier_id, effective_date` | `findByBarrierIdAndEffectiveDate`（幂等更新）| **唯一** |
| | `idx_barrier_area_date` | `area_code, effective_date` | `findByAreaCodeAndEffectiveDate` | 普 |
| | `idx_barrier_type` | `barrier_type` | 按阻断类型筛选 | 普 |
| | `idx_barrier_date` | `effective_date` | `findByEffectiveDate` / `countByEffectiveDate` / `findByEffectiveDateOrderByBarrierTypeAsc` | 普（**本次新增**）|
| `t_mqtt_message_log` | `idx_msg_log_time` | `received_at` | 时间范围检索 / `deleteBefore`（保留策略）| 普 |
| | `idx_msg_log_vin_time` | `sender_id, received_at` | `findBySenderIdOrderByReceivedAtDesc` | 普 |
| | `idx_msg_log_type` | `mqtt_type, received_at` | `findByMqttTypeOrderByReceivedAtDesc` | 普 |
| | `idx_msg_log_msg_id` | `msg_id` | `findByMsgId`（**按 msgId 全链路追溯**）| 普 |

### 4.2 本次核验新增的 3 个索引（判断依据）

这 3 处都是"**复合索引的前导列与查询条件不匹配**"造成的隐性全表扫描：索引存在、名字看起来也对，但优化器用不上。

| 新增索引 | 被漏掉的查询 | 为什么原索引用不上 |
|---|---|---|
| `t_fault_record.idx_fault_time(reported_at)` | `countByReportedAtAfter`、`topErrorCodes` 的父表过滤 | 已有两个索引前导列分别是 `vin`、`max_severity`；"近 30 天"这类纯时间过滤无索引可用，而明细表已有 `error_code` 索引，**父表反而成了整个聚合的瓶颈** |
| `t_track_task.idx_task_status_time(status, started_at)` | `findByStatusOrderByStartedAtDesc`、`countByStatus` | `idx_task_vin_status` 前导列是 `vin`；运营页"只看进行中"的默认视图会导致全表扫描 + filesort |
| `t_map_barrier.idx_barrier_date(effective_date)` | `findByEffectiveDate`、`countByEffectiveDate` | `uk_barrier_id_date` 前导列 `barrier_id`、`idx_barrier_area_date` 前导列 `area_code`，日期单列条件一个都用不上；而"今日阻断点"是本模块最高频查询 |

### 4.3 有意不加索引的地方

| 场景 | 查询 | 为什么不加 |
|---|---|---|
| 报文留痕按处理结果筛选 | `findByProcessStatusOrderByReceivedAtDesc` | `process_status` 基数极低（5 个取值）且分布严重倾斜（99% 是 `OK`），建索引的代价（每次写入多维护一棵树）大于收益；排障时用时间范围 + `idx_msg_log_time` 已足够 |
| 车辆列表按企业 + 在线组合 | `JpaSpecificationExecutor` 动态条件 | 组合爆炸（企业 × 车型 × 在线 × 关键字），不逐个建组合索引；靠 `idx_vehicle_enterprise` 与 `idx_vehicle_online` 各承担一半 |
| 事故描述全文检索 | 前端关键字过滤 | 只在一页 20 条内做客户端过滤；真正需要全文检索时应上 ES，而不是给 `TEXT` 列加索引 |

---

## 5 容量估算与增长预测

### 5.1 单行占用测算

按 InnoDB **DYNAMIC** 行格式、utf8mb4、`VARCHAR` 存 ASCII（1 字节/字符）测算，含行头（5B）、隐藏列（13B）、所有二级索引（二级索引条目含主键回表值）、页内开销：

| 表 | 数据列 | 二级索引 | **单行合计** |
|---|---|---|---|
| `t_vehicle_track_point` | 86 B | 92 B | **178 B** |
| `t_vehicle_state_snapshot` | 184 B | 60 B | **244 B** |
| `t_mqtt_message_log` | 166 B + 溢出页 | 145 B | **726 B**（payload 按 350 B 计，走溢出页）|

> payload 大于约 40 字节时会走**溢出页**（DYNAMIC 行格式），行内只留 20 字节指针 —— 这也是 `VARCHAR(512)` 的 `url` 与 `MEDIUMTEXT` 的 `payload` 在单行成本上差异巨大的原因。

### 5.2 增长速率与磁盘预算

**轨迹表 `t_vehicle_track_point`（1Hz/车）—— 决定性因素**

| 车辆数 | 行数/天 | GB/天 | 保留 30 天 | 保留 90 天 | 保留 180 天 |
|---|---|---|---|---|---|
| 100 | 864 万 | 1.43 | 43 GB | 129 GB | 258 GB |
| 500 | 4320 万 | 7.16 | 215 GB | 645 GB | 1289 GB |
| **1000** | **8640 万** | **14.32** | **430 GB** | **1289 GB** | **2578 GB** |

**状态流水 `t_vehicle_state_snapshot`（60s/车）**

| 车辆数 | 行数/天 | GB/天 | 保留 180 天 |
|---|---|---|---|
| 100 | 14.4 万 | 0.033 | 5.9 GB |
| 500 | 72 万 | 0.164 | 29.5 GB |
| 1000 | 144 万 | 0.327 | **58.9 GB** |

**报文留痕 `t_mqtt_message_log`（原始报文量 ≈ 1.02 条/秒/车）**

| 车辆数 | 原始条/天 | `full` 全量 | `sampled` 5% | `event-only`（≈0.1%）|
|---|---|---|---|---|
| 100 | 881 万 | 6.0 GB/天 | 0.30 GB/天 | 0.006 GB/天 |
| 500 | 4406 万 | 29.8 GB/天 | 1.49 GB/天 | 0.030 GB/天 |
| 1000 | 8813 万 | 59.6 GB/天 | 2.98 GB/天 | 0.060 GB/天 |

按当前生产默认（`sampled` 5% + 180 天）：**1000 台车 ≈ 536 GB**；若误配成 `full`，仅留痕一张表就需要 **10.7 TB**。

**媒体文件（不进数据库，但占磁盘，且是最大的单项）**

按 1080p H.265 ≈ 2 Mbps、单路 20 秒 ≈ 5 MB、每起事故 4 路计算：

| 事故量 | GB/天 | 保留 90 天 |
|---|---|---|
| 10 起/天 | 0.20 | 18 GB |
| 50 起/天 | 0.98 | 88 GB |
| 100 起/天 | 1.95 | 176 GB |

**其余小表**（增长可忽略，但需保留全量以支撑取证）：

| 表 | 1000 台车下估算 | 说明 |
|---|---|---|
| `t_vehicle` | 1000 行，约 0.3 MB | 每车一行 |
| `t_vehicle_static_param` | 约 1 MB/天 | 每次重连一条（按每车每天 5 次）|
| `t_fault_record` + `t_fault_item` | 约 4 MB/天 | 按每车每天 5 条上报 × 2 条明细 |
| `t_accident_event` | 约 2.5 MB/月 | 按每车每月 5 起 |
| `t_media_asset` | 约 2 MB/月（元数据）| 视频本体在磁盘 |
| `t_track_task` / `t_remote_driving_record` / `t_navigation_route` | < 1 MB/天 | 运营侧低频 |
| `t_map_barrier` | 每设施每天一行，通常 < 1000 行/天 | 极小 |

### 5.3 结论：保留天数必须由磁盘反推

**关键结论：轨迹表是唯一需要"按预算反推参数"的表。**

以 1000 台车、MySQL 数据盘 500 GB（预留 30% 给索引重建与备份临时空间，实际可用 350 GB）为例：

| 目标 | 建议配置 |
|---|---|
| 只保轨迹 30 天 | 430 GB —— **超出预算，需扩盘或降低采样** |
| 保轨迹 15 天 | 215 GB —— 可行，且满足"事发后回溯近两周轨迹"的常规取证需求 |
| 保轨迹 30 天 + 状态 180 天 | 430 + 59 = 489 GB —— 需 ≥ 750 GB 数据盘 |
| 保轨迹 90 天 | 1289 GB —— 需 ≥ 2 TB 数据盘，或迁往时序库（见 8.6）|

对应配置项（`application-prod.yml`）：

```yaml
dssad:
  retention:
    track-point-days: 15        # 磁盘反推：500GB 盘 → 15 天
    state-snapshot-days: 180
    batch-size: 5000
```

**三条工程建议**：

1. **先量后配**。上线前用 1 台车实跑 24 小时，`SELECT data_length + index_length FROM information_schema.tables WHERE table_name = 't_vehicle_track_point'` 实测单日增量，再乘车辆数外推。上面的 178 B/行是**测算值**，实测通常在 ±20% 内浮动（受行格式、填充因子、是否启用压缩影响）。
2. **保留天数与取证要求对齐**。文档要求"车端本地至少存 10 天"，平台侧保留期应**不小于**该值，否则会出现"车端还有、平台已删"的倒挂，取证时无法闭环。建议平台侧 ≥ 15 天。
3. **采样是比删数据更早的杠杆**。若磁盘不足，优先降低**留痕采样率**（`full` → `sampled`，一行配置省 95%），而不是缩短轨迹保留期 —— 轨迹是事故回溯的原始证据，留痕有事件类报文兜底。

---

## 6 数据生命周期管理

### 6.1 三类数据的三种命运

| 类别 | 表 | 生命周期 | 依据 |
|---|---|---|---|
| **流水型（有保留期）** | `t_vehicle_track_point`、`t_vehicle_state_snapshot`、`t_mqtt_message_log` | 到期自动删除 | 存储预算（遥测）/ 监管要求（留痕）|
| **取证型（长期保留）** | `t_accident_event`、`t_fault_record`、`t_fault_item`、`t_media_asset` | **不自动删除** | 事故责任认定与监管核查的原始证据 |
| **档案型（永久）** | `t_vehicle`、`t_vehicle_static_param`、`t_track_task`、`t_remote_driving_record`、`t_navigation_route`、`t_map_barrier` | 永久保留（量小）| 车辆准入台账、运营台账、接管台账 |

### 6.2 三个定时任务（时间刻意错开）

| 任务 | 触发 | 目标 | 配置项 | 分批策略 |
|---|---|---|---|---|
| 地图增强补偿拉取 | 每天 **00:05** | （拉取，非清理）| `dssad.regulatory.mapehn-path` | — |
| 报文留痕清理 `MqttAuditService.purgeExpired` | 每天 **03:00** | `t_mqtt_message_log` | `dssad.audit.retention-days` = 180 | 循环删除，每批上限由语句决定 |
| **遥测流水清理 `TelemetryRetentionService.scheduledPurge`** | 每天 **03:30** | `t_vehicle_track_point`、`t_vehicle_state_snapshot` | `dssad.retention.track-point-days` = 90 / `state-snapshot-days` = 180 | **native `DELETE ... LIMIT batch-size`**，每批 5000 行 |

三个任务在时间上错开，避免同时争抢 IO；`03:00` 与 `03:30` 都避开业务高峰。

### 6.3 为什么必须分批删（而不是一条大 DELETE）

在亿级表上执行一条 `DELETE FROM t_vehicle_track_point WHERE ts < ?` 的后果：

1. 事务长时间持有行锁 / 间隙锁，在线写入被阻塞；
2. undo 日志暴涨，回滚段撑满后新事务直接失败；
3. 主从复制延迟飙到小时级（从库要重放同样的删除）；
4. 删除后留下大量空洞页，磁盘空间**并不立即释放**，表反而变得更大。

改成每批 `LIMIT 5000` 后，单次持锁在毫秒级，主从复制与在线查询几乎无感；代价是总耗时变长 —— 这恰恰是优点：把 IO 压力削平摊到整个低峰时段。

### 6.4 实现细节与安全阀

`TelemetryRetentionService` 的四条关键设计（都在类注释里写明了理由）：

| 设计 | 内容 | 原因 |
|---|---|---|
| **事务边界** | `@Transactional` 标在**仓储方法**上，服务层的循环**不标注** | 保证"每批一个事务"。若循环上标注了 `@Transactional`，几千个批次会被合并成一个巨型事务 —— 分批就白做了 |
| **批次上限** | `MAX_BATCHES_PER_RUN = 2000` | 安全阀。首次上线或曾长期停用清理时积压可能极大，一次跑几小时会与其他任务冲突；留尾巴到第二天继续更安全 |
| **两表隔离** | 轨迹表与状态表分别 try/catch | 轨迹表日增是状态表的 60 倍，不应因为其中一张出错就让另一张也不清理 |
| **`0 = 不清理`** | 保留天数为 0 时跳过并打 debug 日志 | 提供"临时关闭"的手段；但生产配置**不允许**为 0，否则单表数周内到十亿行 |

**native SQL 的必要性**：JPQL 不支持 `LIMIT`，而"分批"必须靠 `LIMIT` 实现，因此这两个删除方法都是 `nativeQuery = true`。语句中**不能出现 `ORDER BY`** —— MySQL 的 `DELETE ... LIMIT` 与 `ORDER BY` 互斥。

### 6.5 验证证据（集成测试）

清理逻辑由 `TelemetryRetentionServiceTest` 在真实数据库（H2 `MODE=MySQL`）上验证 4 个用例：

| 用例 | 断言 | 覆盖的风险 |
|---|---|---|
| `purgeOnlyRemovesExpiredTrackPoints` | 删 40 天前 3 条，保留 1 天前 2 条 | **反向错误**：误删保留期内数据（最危险的失败模式）|
| `purgeLoopsUntilAllExpiredRowsRemoved` | 7 条数据 + 批大小 2 → 4 批删净 | 循环终止条件写错导致"留尾巴" |
| `zeroRetentionDaysDisablesPurge` | 保留天数 0 时一行不删 | 误配置清库 |
| `purgeStateSnapshotsIndependently` | 轨迹表关闭时只清状态表 | 两张表互相干扰 |

这四个用例的价值在于：它们能证明 **native SQL 语法正确**、**`@Transactional` 真的生效**（漏标会抛 `TransactionRequiredException`）。用 Mockito 假仓储的写法同样能"全绿"，却完全证明不了这两件最重要的事 —— 而清理任务每天只跑一次，写错要等数周后才以"磁盘告警"的形式暴露。

> 单测 profile（`application-test.yml`）把保留天数设为 **0**：定时任务在测试上下文里跑起来会删掉其他用例插入的数据，造成随机失败的"幽灵用例"。这也说明保留策略的**默认档位设计**是对的 —— 只有显式配置才会清数据。

---

## 7 一致性与变更管理

### 7.1 三方核验方法

同一套表结构存在**三个副本**，任何一处不同步都会造成后果不同的故障：

| 副本 | 位置 | 不同步的后果 |
|---|---|---|
| ① JPA 实体 | `domain/entity/*.java` | —（源头）|
| ② 生成 DDL | `db/schema-mysql.generated.sql` | 比对基线失真，人工核对失去参照 |
| ③ 手写 DDL | `db/schema-mysql.sql` | **生产启动失败**（`ddl-auto=validate`）|

核验命令：

```bash
# 重新生成基线（会先删除旧文件，见 7.4）
cd cloud-platform-server
mvn test -Dtest=SchemaDdlGeneratorTest -Dddl.gen=true

# 与手写脚本逐表比对（列集合 + 索引）
diff <(grep -E '^\s{4}(create table|[a-z_]+ )' db/schema-mysql.generated.sql) \
     db/schema-mysql.sql
```

更细的比对（本次实际执行的方式）：用脚本解析两侧的 `CREATE TABLE` 块，分别提取**列名集合**与**索引名 + 列组合**，做集合差。

### 7.2 本次核验结论

**结论一：13 张表的列集合完全一致。** 实体声明的每一个 `@Column(name=...)` 都能在手写 DDL 中找到，反之亦然（差异仅为解析器把 `BaseEntity` 继承列与 `COMMENT` 关键字误判，人工复核后确认无实际缺失）。

**结论二：LOB 列的实际映射与手写 DDL 一致。** 从生成的 DDL 中实测确认：

| 实体声明 | Hibernate 实际生成 | 手写 DDL | 一致 |
|---|---|---|---|
| `accident_desc` `@Lob length=4000` | `text` | `TEXT` | ✅ |
| `barrier_polygon` `@Lob length=65535` | `text` | `TEXT` | ✅ |
| `payload` `@Lob length=262144` | `mediumtext` | `MEDIUMTEXT` | ✅ |
| `path_points` `@Lob length=1048576` | `mediumtext` | `MEDIUMTEXT` | ✅ |

> 这项核验直接回答了一个重要疑问：**如果 `@Lob` 不给 `length`，会不会被映射成 `tinytext`（255 字节）并静默截断数据？** 实测表明给定 length 后映射正确；而实体注释中"不给 length 会退化为 tinytext"的告警依然有效 —— 那是 Hibernate 对无长度 `@Lob String` 的默认行为。

**结论三：发现并修复了 3 个索引缺口**（依据与原理见 4.2 节），已同步到实体与手写 DDL。`ddl-auto=validate` **不校验索引**，因此这类问题不会在启动时暴露，只能在查询变慢时才发现 —— 这正是本次做"索引 ↔ 查询方法"逐一对照的意义。

**结论四：发现并修复了 3 处 DDL 注释错误。**

| 表.列 | 原注释 | 实际取值 | 影响 |
|---|---|---|---|
| `t_media_asset.status` | `PENDING / AVAILABLE / FAILED` | `PENDING / AVAILABLE / **MISSING**` | 运维按注释排查"文件缺失"时会查不到任何数据 |
| `t_fault_item.error_severity` | `等级 1~5` | `0~7`（0~1 无影响 / 2~3 可恢复 / 4~5 需人工 / 6~7 严重）| 会误导出错误的过滤条件（漏掉 `severity=6/7` 的真正严重故障）|
| `t_map_barrier.barrier_type` | `类型：21 等（见文档附录）` | `20` 禁行区域 / `21` 限速区域 | 排查阻断类型时需翻文档 |

**结论五：发现并修复了 1 处"文档声称存在、代码里没有"的功能缺口（P0）。**

`schema-mysql.sql` 的注释写着"当前版本采用「保留策略定时 DELETE + idx_track_ts」方案（见 `TelemetryService` 的清理任务）"，但 `TelemetryService` **只有刷盘、没有任何清理逻辑**，全项目除报文留痕外没有第二处删除。后果：轨迹表 1000 台车规模下 **14.3 GB/天无上限增长**，约 5 周后单表过 30 亿行，查询、备份、DDL 全部失效，最终只能停机人工清理。

**已修复**：新增 `TelemetryRetentionService`（第 6 章）+ 两个仓储的 native 分批删除方法 + `AppProperties.Retention` 配置 + 4 个集成测试；同时修正 DDL 注释指向正确的类名。修复后全量测试**通过、0 失败**（修复当时为 164 个用例；随测试体系扩充，现为 **200** 个）。

### 7.3 变更流程（改表必守）

```bash
# 1. 改实体（@Column / @Table indexes）
# 2. 重新生成基线
mvn test -Dtest=SchemaDdlGeneratorTest -Dddl.gen=true
# 3. diff 基线，人工把差异落到手写脚本（保留分区/字符集等增强注释）
diff db/schema-mysql.generated.sql db/schema-mysql.sql
# 4. 在预发环境执行
mysql -h$DB_HOST -u$DB_USERNAME -p $DB_NAME < db/schema-mysql.sql
# 5. 启动应用验证（validate 通过即为一致）
mvn spring-boot:run -Dspring-boot.run.profiles=prod
```

三条纪律：

1. **只改实体，不手改生成的类型**。手改 `float(53)` → `double` 虽能通过校验（Hibernate 按类型族比较），但下次生成又会变回来，diff 噪音不断累积。
2. **验证必须跑一次真实启动**。`mvn test` 用的是 H2 + `create-drop`，**完全不校验** MySQL 的 DDL —— 只有 `ddl-auto=validate` 的启动才能发现不一致。
3. **线上加索引用 `ALTER TABLE ... ALGORITHM=INPLACE, LOCK=NONE`**（MySQL 8 加二级索引支持在线 DDL）。若要按本文件重建整张表，请拆成独立的 `ALTER` 语句逐条执行，不要整脚本重跑。

### 7.4 附带修复：DDL 生成脚本的"追加"缺陷

`SchemaDdlGeneratorTest` 生成的基线文件曾被累积到 **3 份完整的 `create table`**（Hibernate 的脚本输出是**追加**模式，而测试没有先删除旧文件）。后果是 diff 充满无意义的重复段落，"比对基线"事实上已经不可用。

**已修复**：生成前显式删除目标文件；删除失败直接抛异常（不允许静默退化为追加）。

---

## 8 部署与运维

### 8.1 建库与初始化

```sql
CREATE DATABASE dssad_cloud DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER 'dssad'@'%' IDENTIFIED BY '<从密钥管理系统注入>';
GRANT SELECT, INSERT, UPDATE, DELETE ON dssad_cloud.* TO 'dssad'@'%';
-- 刻意不给 DROP/ALTER：生产环境的表结构变更由发布流程执行，应用账号只做 DML
```

```bash
mysql -hdssad-db -udssad -p dssad_cloud < cloud-platform-server/db/schema-mysql.sql
```

**字符集必须 `utf8mb4`**：事故描述、故障原因等字段来自车端原文，可能包含 emoji 或生僻字；`utf8mb3` 会在写入时截断或报错。

### 8.2 连接串参数（每个都有具体作用）

```
jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}
  ?useUnicode=true&characterEncoding=utf8        # 与 utf8mb4 表结构配套
  &serverTimezone=Asia/Shanghai                  # 避免 8 小时时差（本项目所有时间按东八区展示）
  &useSSL=true                                   # 生产禁明文
  &rewriteBatchedStatements=true                 # ★ 批量插入生效的前提，缺它 batch_size 形同虚设
  &allowPublicKeyRetrieval=false                 # 避免 MySQL 8 的密钥检索降级
```

> `rewriteBatchedStatements=true` 是最容易被漏掉的一项：漏掉时应用仍然"正常"运行，只是 1000 行/秒的轨迹写入变成 1000 次网络往返，CPU 与延迟都上去了却看不出原因。

### 8.3 虚拟线程下的并发上限

生产启用 `spring.threads.virtual.enabled=true`（Java 21）。此时 **Tomcat 的 `max-threads: 200` 不再是并发上限**，真正的瓶颈变成 **Hikari 连接池 `maximum-pool-size: 20`**。

这个语义变化是正确的：以前是"线程先排队"，现在是"数据库连接先排队"。部署时须注意：

| 项 | 建议 | 说明 |
|---|---|---|
| `maximum-pool-size` | `CPU 核数 × 2 + 有效磁盘数`，8 核取 20 | 连接数与数据库承载能力匹配；盲目调大只会让 MySQL 侧排队 |
| `minimum-idle` | 5 | 避免低峰期连接被回收后突增请求要重建连接 |
| `max-lifetime` | 1200000 ms（20 分钟）| 小于 MySQL `wait_timeout`，避免使用到已被服务端关闭的连接 |
| 监控 | `hikaricp_connections_pending` | 该指标持续 > 0 说明连接池是瓶颈，而不是线程池 |

### 8.4 备份与恢复

| 对象 | 策略 | 说明 |
|---|---|---|
| 小表（档案/事件/故障/任务/接管/导航/地图）| 每日全量 `mysqldump` | 总计 < 1 GB，可完整带走，恢复简单 |
| `t_vehicle_track_point` | **按分区/按天导出**或直接排除 | 全量导出会拖垮主库 IO；建议按保留期分片导出到对象存储 |
| `t_mqtt_message_log` | 按保留期导出压缩 | 留痕是取证数据，**归档优先于删除**：先导出到冷存储，再由清理任务删除 |
| 媒体文件 | 对象存储版本管理 + 生命周期 | 与数据库元数据分开备份；恢复时以 `storage_path` 对齐 |
| 恢复演练 | 每季度一次 | 重点验证：能否在只有 dump 的情况下重建出可启动的环境（含 `ddl-auto=validate` 通过）|

### 8.5 需要盯住的监控指标

| 指标 | 来源 | 阈值/含义 |
|---|---|---|
| 各表 `data_length + index_length` | `information_schema.tables` | 轨迹表日增偏离测算值 ±30% 需复查（行格式/填充因子变化）|
| 清理任务日志 | `[保留策略] 已清理超期遥测：...` | **连续多日删除 0 行且表在增长 = 保留策略未生效**，需立即排查 |
| `hikaricp_connections_pending` | Actuator/Prometheus | 持续 > 0 表示连接池瓶颈 |
| 慢查询 | `slow_query_log`（建议 `long_query_time=1`）| 重点看 `t_vehicle_track_point` 与 `t_mqtt_message_log` 上的全表扫描 |
| 主从延迟 | `Seconds_Behind_Master` | 清理任务执行期间应保持秒级；分钟级说明批次过大 |
| 缓存命中率 | `GET /api/v1/monitor/cache` | L1 命中率骤降通常意味着 TTL 配置或数据分布异常 |

### 8.6 扩容路径（按代价从低到高）

1. **调保留天数 / 采样率**（改配置，零停机）：磁盘不足时的第一选择，见 5.3。
2. **加只读副本**：把大屏统计、报表明细类查询分流到从库。需注意本平台的大屏统计已有 5~60 秒的二级缓存，读副本的收益主要在**历史检索**类查询。
3. **轨迹表按天分区**：把"删除 180 天前数据"从 `DELETE` 变成 `DROP PARTITION`（毫秒级、无碎片）。前置条件与改造步骤见 `schema-mysql.sql` 文末「可选增强 A」—— 需要先把主键改成复合主键 `(ts, id)` 并在 JPA 侧用 `@IdClass`。
4. **轨迹迁往时序库**（如 TDengine / InfluxDB / ClickHouse）：当车辆数超过 2000 台或需要"按空间范围检索轨迹"时，InnoDB 的行存结构不是最优解。迁移的边界是**清晰的**：`t_vehicle_track_point` 是纯追加、只按 `(vin, ts)` 查询、无事务需求，非常适合时序库；而事件、故障、取证数据必须留在 MySQL 以保留事务与唯一约束。

---

## 附录 A 保留策略与相关配置速查

| 配置项 | local | test | prod 默认 | 作用 |
|---|---|---|---|---|
| `dssad.retention.track-point-days` | 7 | 0 | 90 | 轨迹点保留天数；**0 = 不清理**（生产禁用）|
| `dssad.retention.state-snapshot-days` | 14 | 0 | 180 | 状态流水保留天数 |
| `dssad.retention.batch-size` | 2000 | 100 | 5000 | 单批删除行数 |
| `dssad.audit.mode` | full | full | sampled | 报文留痕模式 |
| `dssad.audit.sample-rate` | — | 0.05 | 0.05 | `sampled` 模式采样率 |
| `dssad.audit.retention-days` | 7 | 1 | 180 | 留痕保留天数 |
| `dssad.api.track-simplify-tolerance` | 8.0 | 8.0 | 8.0 | 轨迹抽稀容差（米）|
| `dssad.api.max-page-size` | 200 | 200 | 200 | 分页上限 |
| `dssad.api.history-video-max-span-hours` | 168 | 168 | 168 | 历史视频单次查询最大跨度 |

生产环境支持环境变量注入：`DSSAD_TRACK_RETENTION_DAYS`、`DSSAD_STATE_RETENTION_DAYS`、`DSSAD_RETENTION_BATCH_SIZE`、`DSSAD_AUDIT_MODE`、`DSSAD_AUDIT_SAMPLE_RATE`、`DSSAD_AUDIT_RETENTION_DAYS`。

---

## 附录 B 缺陷与待办清单

### B.1 本次核验发现并已修复

| 编号 | 类型 | 问题 | 修复 |
|---|---|---|---|
| **D-01** | **功能缺口（P0）** | 注释声称存在遥测清理任务，代码中不存在 → 轨迹表无上限增长（1000 台车 14.3 GB/天）| 新增 `TelemetryRetentionService` + 2 个 native 分批删除方法 + `AppProperties.Retention` + 4 个集成测试 |
| D-02 | 索引缺口 | `t_fault_record` 缺 `reported_at` 单列索引，"近 N 天"统计走全表 | 新增 `idx_fault_time` |
| D-03 | 索引缺口 | `t_track_task` 缺 `(status, started_at)`，运营页默认视图全表扫描 + filesort | 新增 `idx_task_status_time` |
| D-04 | 索引缺口 | `t_map_barrier` 两个复合索引前导列都不匹配"按日期"查询 | 新增 `idx_barrier_date` |
| D-05 | 注释错误 | `t_media_asset.status` 注释写 `FAILED`，实际为 `MISSING` | 更正注释 |
| D-06 | 注释错误 | `t_fault_item.error_severity` 注释写 `1~5`，实际 `0~7` | 更正注释并补齐分级语义 |
| D-07 | 注释错误 | `t_map_barrier.barrier_type` 注释含糊（`21 等`）| 更正为 `20 禁行 / 21 限速` |
| D-08 | 工具缺陷 | DDL 生成脚本采用追加模式，基线文件累积了 3 份 `create table`，diff 失效 | 生成前删除目标文件 |
| D-09 | 注释错误 | 清理任务注释指向 `TelemetryService`，实际应为 `TelemetryRetentionService` | 更正 |

### B.2 待决策（需业务/运维确认，非代码缺陷）

| 编号 | 事项 | 现状 | 建议 |
|---|---|---|---|
| **T-01** | **媒体文件的保留策略未定义** | 数据库元数据永久保留，磁盘文件无任何清理机制 | 需明确"事故视频保存多久"。按 100 起事故/天估算，90 天约 176 GB；建议：事故视频 ≥ 1 年（监管要求），历史录像按 30 天滚动 |
| T-02 | 轨迹保留天数与磁盘预算的匹配 | 默认 90 天，1000 台车需 1.29 TB | 上线前用实车 24 小时实测单日增量后按 5.3 节反推配置 |
| T-03 | 报文留痕 `sampled` 采样是否满足监管要求 | 默认 5%，事件类报文始终全量 | 需监管方确认"非事件报文是否需要全量留存"。若需，预留 10.7 TB（1000 台车 × 180 天）|
| T-04 | 是否启用轨迹表按天分区 | 当前用"保留策略 + 单列索引" | 数据量到十亿行级、或"删除 180 天数据"耗时超过维护窗口时启用（步骤见 DDL 文末「可选增强 A」）|
| T-05 | 远驾接管配对依赖"最近未关闭记录" | 协议无接管单号 | 若同一车可能并发两次接管，需在协议侧补"接管单号"（已登记至接口文档附录 C）|
| T-06 | 无独立只读账号 | 应用账号权限已最小化（无 DDL） | 报表/大屏若直连库，建议单独建只读账号并走读副本 |

---

## 附录 C 变更记录

| 版本 | 日期 | 变更内容 |
|---|---|---|
| v1.0 | 2026-09-23 | 首版。覆盖 13 张表的结构/索引/容量/生命周期；新增 3 个索引、修复 3 处注释错误、补齐遥测清理任务（D-01 ~ D-09）|

---

> **文档维护提示**：本文件的容量测算基于 5.1 节的行大小模型，属于**测算值**。首次上线后请用真实数据替换 5.2 节的表格（`SELECT table_rows, data_length, index_length FROM information_schema.tables`），并把实测值写入附录 C。





