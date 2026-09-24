# 车路通 DSSAD 产品云平台 — 详细设计说明书

> **文档版本**：v1.0.0  
> **对应代码版本**：`dssad-cloud-platform` 1.0.0-SNAPSHOT  
> **编制**：DSSAD 云平台研发组  
> **上游依据**：《平台数据对接接口文档 v1.0.0》  
> **配套文档**：《DSSAD 云平台接口文档》《DSSAD 云平台数据库设计说明书》

---

## 0 文档说明

### 0.1 编写目的

本文档回答一个具体问题：**这套代码是怎么组织的，以及为什么这么组织**。

它与接口文档、数据库设计文档的分工是明确的：

| 文档 | 回答的问题 | 不回答的问题 |
|---|---|---|
| 接口文档 | 对外**能看到什么**：路径、字段、错误码、Topic | 这些接口内部怎么实现 |
| 数据库设计说明书 | 数据**存在哪、长什么样**：表、列、索引、容量 | 数据怎么被写入与消费 |
| **本说明书** | 代码**怎么运行**：分层、线程、状态机、失败路径、扩展点 | 逐个字段的含义 |

因此本文以**运行时视图**为主线：先讲进程里有哪些线程、它们如何协作（第 2 章），再讲每条业务链路如何在这些线程上跑通（第 3–4 章），最后讲安全、缓存、可靠性与部署（第 5–9 章）。

主要读者：接手本项目的开发工程师、做代码评审的技术负责人、需要评估改动影响面的运维与测试人员。

### 0.2 阅读路径建议

| 你的目的 | 建议阅读顺序 |
|---|---|
| 第一次接手项目 | 1.2 逻辑分层 → 1.3 包结构 → 2.1 线程全景 → 4.1 遥测 → 4.3 事故闭环 |
| 要改某个接口 | 先查《接口文档》定位路径 → 用附录 A 的「接口 ↔ 类」索引找到入口类 → 看本文对应业务模块小节 |
| 要排查线上问题 | 7.4 可观测性 → 7.1 失败模式与降级表 → 2.7 停机顺序 → 9.3 配置项全景 |
| 要做性能优化 | 2.3 虚拟线程 → 2.4 批量刷盘 → 6.3 二级缓存 → 6.4 限流器 → 《数据库设计》第 5 章容量 |
| 要评估协议变更影响 | 3.1 Topic 模型 → 3.3 处理流水线 → 10 缺陷与待改进清单 |

### 0.3 术语与缩写

| 术语 | 含义 |
|---|---|
| **DSSAD** | 数据存储与事故取证（本项目的产品代号） |
| **车云** | 车端 ↔ 平台云端之间的 MQTT 通道（第 5 章），Topic 为 5 段或 6 段 |
| **云云** | 企业云端 ↔ 监管平台云端之间的 MQTT 通道（第 6 章），Topic 为 4 段 |
| **企业云端** | 本平台。既作为 MQTT Client 接入监管平台，又对车端提供 HTTP 上传接口 |
| **监管平台 / 准入平台** | 上游平台（招商智行），本平台向其上报数据、向其拉取地图增强信息 |
| **通道 A / 通道 B** | A：车端直连监管平台；B：车端接企业自建网关。本项目按 B 形态实现，兼容 A |
| **应用层 ACK** | 业务报文中的 `res` 字段回复机制，用于确认「对端业务处理成功」，区别于 MQTT 自带的 QoS 1 PUBACK |
| **取证闭环** | 事故上报 → 下发媒体请求 → 车端受理 → 四方向视频上传 → 归档完成这一整条链路 |
| **L1 / L2 缓存** | L1 = 进程内 Caffeine；L2 = Redis（多实例共享） |
| **滑动窗口计数** | 限流算法：上一窗口计数按时间流逝线性衰减后与当前窗口相加 |

### 0.4 参考资料

| 编号 | 名称 |
|---|---|
| [1] | 《平台数据对接接口文档 v1.0.0》（PDF，68 页） |
| [2] | 《DSSAD 云平台接口文档》（`docs/api/`） |
| [3] | 《DSSAD 云平台数据库设计说明书》（`docs/db/`） |
| [4] | 源码：`cloud-platform-server/src/main/java/com/genvict/dssad/cloud/` |
| [5] | 设计基线 DDL：`cloud-platform-server/db/schema-mysql.sql` |
| [6] | 压测脚本：`cloud-platform-server/perf/load_test.py` |

---

## 1 系统架构

### 1.1 系统定位与外部边界

本平台处在**车端**与**监管平台**之间，是一个「双向转接 + 数据沉淀 + 事件驱动」的企业侧云端。它有四个外部交互面：

| 交互面 | 对方 | 协议 | 谁是主动方 | 实现位置 |
|---|---|---|---|---|
| ① 车云 MQTT | 车端（或企业自建网关） | MQTT 3.1.1 | 双向上行/下行 | `mqtt/` 全包 |
| ② 云云 MQTT | 监管平台 | MQTT 3.1.1 | 双向上行/下行 | `mqtt/` 全包 |
| ③ 对外 HTTP | 监管平台 | REST/JSON + HMAC | 监管平台调本平台 | `web/EnterpriseApiController` |
| ④ 管理 HTTP | 运营 Web 控制台 | REST/JSON + X-Token | 浏览器调本平台 | `web/*Controller`（除 ③） |

此外还有三个**本平台主动调用**的外部依赖：

| 依赖 | 用途 | 触发方式 | 实现位置 |
|---|---|---|---|
| 监管平台 HTTP | 换取 MQTT 密码、换取 token、拉取当日地图增强 | 按需 + 每日 00:05 | `integration/RegulatoryGateway` |
| SRS 流媒体 | 生成实时视频拉流地址 | 请求时拼接 | `service/MediaService` |
| 数据库 / Redis / 磁盘 | 持久化、去重、缓存、媒体文件 | 全程 | `domain/`、`common/store` |

> **注意**：平台**不接入任何第三方地图 SDK**。轨迹与阻断设施在前端按本地平面等比例投影绘制，原因是 GCJ-02 坐标合规与底图加载失败风险（详见 8.6）。

### 1.2 逻辑分层

平台采用严格的分层，**依赖方向单向向下**，禁止反向或跨层调用：

```
┌─────────────────────────────────────────────────────────────────────┐
│  ④ web  ──  HTTP 适配层                                              │
│     EnterpriseApiController（对外，HMAC 保护）                        │
│     AuthController / DashboardController / VehicleController / ...    │
│     职责：参数绑定、@Valid 校验、调用 Service、包装 ApiResponse         │
│     禁止：写业务分支、直接调 Repository                               │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────────┐
│  ③ mqtt  ──  MQTT 适配层                                             │
│     core/  连接管理、入站队列、路由、重发调度、离线队列                 │
│     handler/  10 个报文处理器（每种类型一段业务逻辑）                   │
│     topic/    Topic 构造与解析（严格互逆）                            │
│     model/    MqttType 协议单一事实来源、AckCode、UploadPriority        │
│     dto/      上/下行报文 DTO（record，与协议字段一一对应）             │
│     职责：Topic→类型→处理器分发、通用字段校验、ACK 回复、留痕           │
│     禁止：直接访问 Repository（必须经 Service）                        │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────────┐
│  ② service  ──  业务逻辑层                                           │
│     TelemetryService / EventService / OperationService /              │
│     AccidentMediaService / MediaService / MapBarrierService /         │
│     RemoteCockpitService / TrackQueryService / VehicleService /       │
│     DashboardService / MqttCommandService / MqttAuditService /        │
│     TelemetryRetentionService                                        │
│     职责：事务边界、业务规则、状态机推进、缓存声明                      │
│     禁止：感知 HTTP / MQTT 协议细节（参数已由上层解好）                 │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────────┐
│  ① domain  ──  领域层                                                │
│     entity/  13 个 JPA 实体                                           │
│     repository/  13 个 Spring Data 仓储                              │
│     dict/  FaultCodeDictionary（29 项故障码字典）                      │
└─────────────────────────────────────────────────────────────────────┘

横切层（被各层使用，不反向依赖业务）：
  common/   api（ApiResponse/ErrorCode）、exception、model（PageResult）、
            store（StateStore 双实现）、cache（二级缓存）、util（雪花/JSON/时间/抽稀）
  config/   AppProperties（全部业务配置）、CacheConfig、WebConfig
  security/ AdminTokenInterceptor、EnterpriseSignatureFilter、TokenService
  ratelimit/ RateLimiter
  integration/ RegulatoryGateway、RegulatorySigner、MqttCredentialProvider
```

**分层的实际价值**（不是形式主义）：

1. **测试可替换**：`service` 不依赖协议对象，`mqtt` 与 `web` 各自被测试覆盖而不互相牵连。例如 `PlatformEndToEndTest` 用同一批 Service 同时验证 MQTT 入口与 HTTP 入口。
2. **协议变更隔离**：协议字段变化只影响 `mqtt/dto` 与 `mqtt/model`；`web/dto` 同理。业务层拿到的是领域对象。
3. **出口收口**：所有报文发送都经 `MqttCommandService`，所有 Topic 拼装都经 `TopicBuilder`（详见 3.1），避免「某个 Service 自己拼串导致消息静默丢失」。

### 1.3 包结构与职责

| 包 | 文件数 | 职责 | 关键类 |
|---|---|---|---|
| `common/api` | 2 | 统一响应体与错误码字典 | `ApiResponse`、`ErrorCode`（12 个码 / 9 个区间） |
| `common/exception` | 2 | 业务异常与全局异常处理 | `BizException`、`GlobalExceptionHandler` |
| `common/model` | 1 | 统一分页结果 | `PageResult` |
| `common/store` | 3 | 键值状态存储抽象 + 两种实现 | `StateStore`、`InMemoryStateStore`、`RedisStateStore` |
| `common/cache` | 3 | 二级缓存（Caffeine L1 + Redis L2） | `TwoLevelCache`、`TwoLevelCacheManager`、`CacheNames` |
| `common/util` | 5 | 无状态工具 | `SnowflakeIdGenerator`、`TrackSimplifier`、`JsonUtils`、`TimeUtils`、`UuidUtils` |
| `config` | 4 | 配置绑定 | `AppProperties`（12 个嵌套 record）、`CacheConfig`、`WebConfig` |
| `security` | 4 | 两套鉴权 + 请求体缓存包装 | `AdminTokenInterceptor`、`EnterpriseSignatureFilter`、`TokenService` |
| `ratelimit` | 1 | 滑动窗口限流 | `RateLimiter` |
| `integration` | 4 | 上游平台 HTTP 对接与凭据管理 | `RegulatoryGateway`、`RegulatorySigner`、`MqttCredentialProvider` |
| `domain/entity` | 14 | JPA 实体 | `AccidentEvent`（含取证状态机）、`MqttMessageLog`、`VehicleTrackPoint` |
| `domain/repository` | 13 | Spring Data 仓储 | 含 4 个原生 SQL 分批删除 / 批量更新 |
| `domain/dict` | 1 | 故障码字典 | `FaultCodeDictionary`（29 项 + 分类） |
| `mqtt/core` | 10 | MQTT 基础设施 | `PahoMqttClientManager`、`InboundMessageQueue`、`MqttMessageRouter`、`PendingAckRegistry`、`OfflineMessageQueue`、`MqttAckRetryScheduler` |
| `mqtt/handler` | 12 | 报文处理器 | `AccidentMessageHandler`、`LowBaseMessageHandler`、`MapehnDownstreamHandler` 等 |
| `mqtt/topic` | 2 | Topic 互逆构造/解析 | `TopicBuilder`、`TopicParser` |
| `mqtt/model` | 6 | 协议模型 | `MqttType`（18 类报文的唯一事实来源）、`AckCode`、`UploadPriority`、`TopicInfo` |
| `mqtt/dto` | 5 | 报文 DTO | `CarUpMessages`、`EnterpriseUpMessages`、`DownMessages`、`AckMessage` |
| `service` | 13 | 业务逻辑 | 见 1.2 |
| `web` | 11 | HTTP 控制器 | `EnterpriseApiController`（对外）、`MqttMonitorController`（运维） |
| `web/dto` | 1 | 对外接口 DTO | `EnterpriseApiDtos` |

### 1.4 三通道数据流总览

```
                         ┌──────────────────────────────────────┐
                         │        监管平台（招商智行）            │
                         └───┬──────────────┬───────────────┬───┘
                   ② 云云 MQTT │     ③ 对外 HTTP │      ⑦ 反向 HTTP
                             │              │        （本平台调用）
                             ▼              ▼               ▲
┌───────────────────────────────────────────────────────────┴───────┐
│                     DSSAD 企业云端（本平台，端口 8080）             │
│                                                                   │
│  [PahoMqttClientManager]  [EnterpriseSignatureFilter]  [RegulatoryGateway]
│        │                                                            │
│   messageArrived(topic,msg)  ← 只入队，微秒级                        │
│        ▼                                                            │
│  [InboundMessageQueue]  有界 20000，满则丢最旧                        │
│        ▼  (4 个虚拟线程 take)                                        │
│  [MqttInboundWorker] ──► [MqttMessageRouter]  8 步流水线（见 3.3）    │
│                              │                                      │
│                        [10 个 MessageHandler]                       │
│                              ▼                                      │
│                     [Service 层业务处理]                             │
│                              │                                      │
│                    ┌─────────┴──────────┐                           │
│                    ▼                    ▼                           │
│           [遥测内存缓冲]          [直接事务落库]                       │
│           (1s 批量 saveAll)      (事故/故障/任务)                     │
│                    │                    │                           │
│                    └────────┬───────────┘                           │
│                             ▼                                       │
│                    [MySQL 13 张表] + [StateStore] + [二级缓存]        │
└───────────────────────────────────────────────────────────────────┘
                             ▲
                   ① 车云 MQTT │       ⑤ HTTP 上传（multipart，200MB）
                             │
                    ┌────────┴─────────┐
                    │  车端 / 企业网关  │
                    └──────────────────┘
```

各通道的核心差异：

| 维度 | ① 车云 MQTT | ② 云云 MQTT | ③ 对外 HTTP | ④ 管理 HTTP |
|---|---|---|---|---|
| Topic/路径形态 | 5~6 段（含 vehicleType/vin） | 4 段（含 enterpriseId） | `/enterprise/api/v1/**` | `/api/v1/**` |
| 鉴权 | Broker 用户名密码（动态换取） | 同左 | HMAC-SHA256 + 时间窗 + Nonce | `X-Token` 头 |
| QoS | 0（遥测）/ 1（事件） | 1 | — | — |
| 是否回复 | 事件类需回 `res` | 全部需回 `res` | 统一 `{code,...}` | 统一 `{code,...}` |
| 失败重试 | 应用层 ACK 重发（5s×3 / 3s×5） | 同左 | 上游侧重试；本平台无内部重试 | 前端重试 |

### 1.5 关键架构决策清单

下表是本项目的 15 个**非显然**的技术决策。每条都给出理由与代价——决策的价值在于知道代价。

| 编号 | 决策 | 理由 | 代价 / 代价控制 |
|---|---|---|---|
| **A-01** | MQTT 回调只入队，业务在独立线程消费 | Paho 回调线程同时负责心跳与 PUBACK；在其中做数据库 IO 会触发断线重连风暴 | 增加一跳延迟（微秒级）与一个队列的状态；队列容量 20000 ≈ 10 秒缓冲 |
| **A-02** | 消费线程用虚拟线程（Java 21） | 消费循环是纯阻塞型；平台线程栈按线程预留 1MB，加大并发度会撞内存墙 | 需留意 pinning（`-Djdk.tracePinnedThreads` 可诊断）；可配置开关退回平台线程做 A/B |
| **A-03** | 遥测（1Hz 轨迹）走内存缓冲 + 每秒批量落库 | 千车规模下逐条 INSERT = 1000 次事务/秒；批量 + `rewriteBatchedStatements` 提升 5~10 倍 | 进程崩溃会丢最后 1 秒数据；用 `@PreDestroy` 尽量刷完，并接受该损失（采样点可由车端重发补齐） |
| **A-04** | 应用侧分配主键（雪花），不用数据库自增 | Hibernate 在 `IDENTITY` 策略下会**关闭 JDBC 批量插入**（每条都要回读主键），吞吐掉一个数量级 | 需处理时钟回拨（≤5ms 自旋，超过抛异常快速失败）；多实例需保证 workerId 不同 |
| **A-05** | 自研二级缓存而非 Spring 默认单级 | 大屏总览要 5s 时效、故障榜可容忍 60s，统一 TTL 只能迁就最短的，白白丢命中率 | 需自行处理序列化与降级（白名单反序列化、L2 异常退化 L1）；见 6.3 |
| **A-06** | 限流用滑动窗口计数而非令牌桶 | 高频路径上网络往返才是瓶颈；计数判定只需 2 次 GET + 1 次 INCR | 精度略低（可能微超发）；被拒请求不计入防「永久封禁」；见 6.4 |
| **A-07** | 不引入 Spring Security，用拦截器 + 令牌 | 需求很窄（单一角色、内网可信）；完整框架带来配置复杂度与启动开销 | 无 RBAC、无方法级权限；待出现多角色需求时平滑升级 |
| **A-08** | 关闭 Paho 自带 `automaticReconnect` | 自带重连的退避序列不可配，且无法在重连成功回调中插入「订阅恢复 + 离线补传」 | 需自行维护重连调度器（单线程守护线程）；见 3.2 |
| **A-09** | 媒体上传接口走「业务态授权」而非 HMAC | body 是可达 200MB 的二进制，读进内存算摘要会炸内存 | 安全性依赖「车端只能对平台请求过的事件上传」；ackMsgId 校验 + vin 一致性校验双重把关 |
| **A-10** | 事故媒体上传接口按监管平台相同的 Path 实现 | 通道 B 形态下车端只与企业云端通信，若本平台不实现该接口，取证链彻底断裂 | 与监管平台接口存在语义重叠（同 Path 两处部署），需靠部署拓扑区分 |
| **A-11** | 轨迹查询两段式降采样（SQL 时间桶 + DP 抽稀） | 只用 SQL 会保留大量直线上冗余点；只用抽稀要先把几十万行读进内存 | 抽稀后为近似轨迹；因此响应里同时返回 `rawCount` 与 `returnedCount`，前端明示 |
| **A-12** | 超期数据分批 `DELETE ... LIMIT`（native） | 一条大 DELETE 会长时间持锁、生成巨大 undo，拖垮主从复制 | 需保证 `@Transactional` 标在仓储方法上而非循环上（否则分批白做）；见 6.x 与 D-01 |
| **A-13** | 前端不接第三方地图 SDK，用本地等比例投影 | GCJ-02 坐标合规；避免底图 CDN 不可用导致整页失能 | 无路网/POI 背景；仅满足「轨迹形状与阻断多边形位置关系」的判读需求；见 8.6 |
| **A-14** | 出站报文统一走 `MqttCommandService`，Topic 统一走 `TopicBuilder` | 手工拼串的 Topic 拼错不会报错，只是消息静默丢失（最贵的一类故障） | 增加一层间接（可忽略）；换来 Topic 可单测（`TopicParserAndBuilderTest` 验证互逆） |
| **A-15** | 单测环境彻底关闭缓存（`spring.cache.type=none`） | 有缓存时 A 用例注入的报文会被 B 用例从缓存读到，测试随机失败且极难定位 | 集成测试需显式装配 `DashboardCacheIntegrationTest` 验证 `@Cacheable` 真的生效 |

---

## 2 运行时视图：线程与并发模型

这一章是理解本平台的关键。**几乎所有线上疑难问题最终都落到「哪个线程在做什么、被谁阻塞」**。

### 2.1 线程全景

进程内共有 **6 类线程**，来源与职责如下：

| # | 线程名 | 数量 | 归属 | 类型 | 职责 | 阻塞点 |
|---|---|---|---|---|---|---|
| 1 | `http-nio-8080-exec-*` | 20~200 | Tomcat | 平台线程 | 处理 HTTP 请求（管理 API + 对外 API） | JDBC 往返、上游 HTTP 调用 |
| 2 | `MQTT Call: e_DSSAD-ENT-0001` | 1 | Paho 内部 | 平台线程 | 心跳保活、PUBACK 发送、触发 `messageArrived` | 网络 IO |
| 3 | `mqtt-consumer-0..3` | 4（可配） | `MqttInboundWorker` | **虚拟线程** | 从入站队列取报文 → 路由 → 业务处理 | `queue.take()`、JDBC |
| 4 | `mqtt-reconnect` | 1 | `PahoMqttClientManager` | 守护平台线程 | 指数退避重连调度 | `schedule()` 定时等待 |
| 5 | `scheduling-1` | 1（默认） | Spring `@Scheduled` | 平台线程 | 全部 5 个定时任务（见 2.5） | 任务执行期间 |
| 6 | `HikariPool-1 housekeeper` 等 | 若干 | HikariCP | 守护平台线程 | 连接池维护 | — |

**关键观察**：

- 线程 3 是**虚拟线程**，所以「增加消费并发度」是一个廉价旋钮（见 2.3）。
- 线程 5 只有 **1 个**。这意味着**所有定时任务串行执行**——如果某个任务跑很久，会推迟其他任务。这也是 2.5 节刻意把任务时间错开的原因。
- 线程 1 的 200 上限与 Hikari 连接池（默认 10）是两回事：**真正并发访问数据库的请求数受连接池限制，不是受 Tomcat 线程数限制**。虚拟线程方案下连接池会先成为瓶颈（见 9.3）。

### 2.2 为什么 MQTT 回调必须「只入队」

这是全平台最重要的稳定性设计（决策 A-01）。先看错误做法的后果：

```
❌ 错误做法：在 Paho 回调里直接做业务
   messageArrived(topic, msg) {
       telemetryService.onLowBase(...)   ← 一次数据库写入，慢查询时 1~3 秒
       // 此时：Paho 回调线程被占住
       //   → keep_alive(60s) 心跳发不出去 → Broker 判定超时 → 断开
       //   → 断线触发重连 → 重连后订阅恢复瞬间收到大量积压报文 → 再次阻塞
       //   → 「重连风暴」自我放大，服务彻底不可用
   }
```

正确做法（`PahoMqttClientManager.ClientCallback`）：

```java
@Override
public void messageArrived(String topic, MqttMessage message) {
    // 只做入队：绝不在此执行数据库/HTTP 操作（否则会阻塞心跳与 PUBACK）
    byte[] payload = message.getPayload();
    MqttEnvelope envelope = MqttEnvelope.of(topic, payload);
    if (!inboundQueue.offer(envelope)) {
        log.warn("[MQTT] 入站队列已满，已丢弃最旧报文 topic={} 累计丢弃={}",
                topic, inboundQueue.droppedCount());
    }
}
```

`MqttEnvelope.of` 里做了一次 JSON 解析——这**不违反**原则，因为：解析是纯 CPU、单次 O(长度)，一条 1KB 报文在微秒级；而且它能保证后续 Router/Handler 不重复解析（一条报文在链路中只解析一次）。

**队列的边界行为**（`InboundMessageQueue`）：

| 属性 | 取值 | 设计理由 |
|---|---|---|
| 实现 | `ArrayBlockingQueue` | 有界 + 无锁冲突低 + 内存确定 |
| 容量 | 20 000 | 千车 1Hz ≈ 1000 条/秒 → 约 20 秒缓冲 |
| 满时行为 | **丢弃最旧**的一条并计数 | 积压说明消费能力不足；此时「保留最新」比「保留全部历史」更有价值（历史可由车端重发或离线补传补齐） |
| 监控 | `size()`、`droppedCount()` | 经 `/api/v1/monitor/mqtt` 暴露；`droppedCount` 持续增长即为容量预警信号 |

### 2.3 入站消费：为什么用虚拟线程

`MqttInboundWorker` 用 `Thread.ofVirtual()` 承载消费循环。判断依据是**工作负载的形状**：

| 特征 | 本场景 | 结论 |
|---|---|---|
| CPU 占用 | 极低（JWT 校验、实体组装） | 不是计算密集型 |
| 阻塞占比 | 绝大多数时间阻塞在 `queue.take()` 或 JDBC 往返 | 典型 IO 阻塞型 |
| 需要并发度吗 | 车端报文处理里混着 DB 写与 HTTPS 调用，串行时单条几十毫秒 | 需要，且希望并发度可调 |

平台线程的问题在于**栈是按线程预分配的**（默认 1MB）：

```
平台线程 4 个消费者  = 4 MB 常驻栈   ← 看起来无所谓
平台线程 64 个消费者 = 64 MB 常驻栈  ← 「想调大并发度」时立刻撞墙
虚拟线程 64 个消费者 ≈ 几十 KB      ← 单机可轻松跑数万
```

因此虚拟线程在这里的收益不是「省内存」，而是 **把 `consumer-threads` 变成一个可以放心调大的旋钮**。

**为什么用裸 `Thread` 而不是 `ExecutorService`**：这里是「固定数量的长驻阻塞循环」，需要精确控制线程名与优雅停机语义；线程池的队列、拒绝策略、空闲回收等机制在此场景没有收益，反而增加理解成本。

**虚拟线程的两个约束**（写代码时必须知道）：

1. 虚拟线程**恒为守护线程**，调用 `setDaemon(true)` 会抛 `UnsupportedOperationException`。这与运行语义一致：进程退出时消费者随之中止，未处理报文由车端重发补齐。
2. 虚拟线程在 `synchronized` 块中阻塞会导致 **pinning**（载体线程被钉住）。本项目的消费路径没有长 `synchronized` 临界区（`OfflineMessageQueue` 的锁只保护堆操作，微秒级）。排查手段：

```bash
# 启动参数：把 pinning 事件打到 stdout
java -Djdk.tracePinnedThreads=full -jar dssad-cloud-platform.jar

# 或 A/B 对比：关掉虚拟线程退回平台线程，观察吞吐与延迟差异
java -jar ... --dssad.mqtt.virtual-threads=false
```

**消费循环的容错**（`MqttInboundWorker.consumeLoop`）：

```java
try {
    router.route(envelope);
    processedCount.incrementAndGet();
} catch (Throwable t) {
    // Throwable 而非 Exception：路由内部若因 OOM 等 Error 抛出，也不能让消费线程死掉
    failedCount.incrementAndGet();
    log.error("[入站] 报文处理出现未捕获异常 topic={}", envelope.topic(), t);
}
```

捕获 `Throwable` 是刻意的：`OutOfMemoryError` 从某条报文里抛出时，若只捕 `Exception`，消费线程会直接终止——**4 个消费者死一个，吞吐降 25%，而监控上看不出来**。

### 2.4 批量刷盘（遥测链路）

1Hz 位置报是平台上最高频的写入（千车规模 1000 条/秒）。`TelemetryService` 采用「内存缓冲 + 每秒批量落库」：

```
【mqtt-consumer-N（虚拟线程）】          【scheduling-1】
  onLowBase(vin, type, payload)
    ├─ 组装 VehicleTrackPoint
    ├─ trackBuffer.offer(point)      ──┐
    ├─ stateStore.put(position)        │  ConcurrentLinkedQueue
    └─ vehicleService.touchHeartbeat() │
       （单条 UPDATE，见 4.2）          │
                                       ▼ 每 1s（@Scheduled fixedDelay=1000）
                          flush()
                            ├─ drain(trackBuffer) → 分批 saveAll（每批 ≤ 5000）
                            └─ drain(stateBuffer) → saveAll
```

**关键参数与理由**：

| 参数 | 取值 | 理由 |
|---|---|---|
| `MAX_BUFFER_SIZE` | 100 000 | 超限丢弃并计数（`droppedCount`）；宁可丢采样点也不能 OOM |
| `FLUSH_BATCH_SIZE` | 5 000 | 单次事务过大同样有害（undo 膨胀、锁持有时间长） |
| `hibernate.jdbc.batch_size` | 200 | 配合 `order_inserts=true` 让 Hibernate 真正攒批 |
| MySQL `rewriteBatchedStatements` | `true` | 把 N 条 INSERT 合并为多值 INSERT，这是吞吐提升的主要来源 |
| 刷盘方法**不加** `@Transactional`？ | **加**（`flush()` 上有 `@Transactional`） | 而入队方法 `onLowBase` **不加**——它只入队，不碰数据库 |

**为什么 `onLowBase` 不加 `@Transactional`**：这个方法会被 MQTT 消费线程调用，如果加了事务，事务会跨越「入队 + 状态缓存写入 + 心跳 UPDATE」，把一条本该微秒级的调用变成一次数据库事务，且持锁到方法返回。**入队不需要事务**，这是它快的原因。

### 2.5 定时任务全景

全部 `@Scheduled` 任务共 5 个，全部由 `scheduling-1` 单线程串行执行：

| 时间/间隔 | 任务 | 类 | 作用 | 失败处理 |
|---|---|---|---|---|
| 每 1 秒 | `scheduledFlush()` | `TelemetryService` | 遥测缓冲刷盘 | 抛异常则本批留到下轮 |
| 每 1 秒 | `tick()` | `MqttAckRetryScheduler` | 重发到期未确认报文 + 补传离线队列 | 未连接则直接跳过 |
| 每天 00:05 | `scheduledPull()` | `MapBarrierService` | 拉取当日地图增强（推送通道兜底） | try/catch 记 error，下个周期重试 |
| 每天 03:00 | `purgeExpired()` | `MqttAuditService` | 清理超期报文留痕 | 分批删除，单批失败中断本轮 |
| 每天 03:30 | `scheduledPurge()` | `TelemetryRetentionService` | 清理超期轨迹点与状态流水 | 两表隔离，单表失败不影响另一表 |

**为什么清理任务定在 03:00 与 03:30 而不是同一时刻**：

1. `scheduling-1` 只有 1 个线程，同刻只会串行执行，等于把总时长叠加；
2. 两个任务都是重 IO 的批量删除，错开可避免同时向数据库施压；
3. 03:00 是业务绝对低谷（远低于 00:05 的拉取时段）。

**为什么 00:05 拉取不与清理冲突**：00:05 拉取是轻量的（一次 HTTP + 数十条 upsert），与 03:00 的批量删除相差近 3 小时，互不影响。

**一个需要注意的行为**：`fixedDelay` 与 `fixedRate` 的差别。本项目两个每秒任务都用 `fixedDelay`——即「上一次执行结束后再等 1 秒」。若某次刷盘耗时 3 秒，下一轮在 4 秒后开始，不会堆积任务；代价是实际频率会低于 1Hz（但缓冲会吸收，不丢数据）。

### 2.6 并发安全手段清单

共享状态集中在 6 处，各自的手段与理由：

| 共享状态 | 类 | 手段 | 为什么够用 |
|---|---|---|---|
| 入站队列 | `InboundMessageQueue` | `ArrayBlockingQueue` | JDK 自带线程安全，无需额外锁 |
| 遥测缓冲 | `TelemetryService.trackBuffer` / `stateBuffer` | `ConcurrentLinkedQueue` | 多生产者单消费者；`poll()` 天然消费一次 |
| 离线队列 | `OfflineMessageQueue` | `ReentrantLock` + `PriorityQueue` | 需要「容量满时淘汰堆内最低优先级元素」，`PriorityBlockingQueue` 没有这个能力；离线补传非高频，锁竞争可忽略 |
| 待确认表 | `PendingAckRegistry` | `ConcurrentHashMap` + 不可变 `Pending` record | 所有变更都是 `put/replace/remove` 原子操作；不可变记录避免「读一半被改」 |
| 状态存储（内存版） | `InMemoryStateStore` | `ConcurrentHashMap` + 惰性过期 | 读时判定过期 + 写时按容量清理；**不做后台扫描线程**（定时全表扫描在大键量下反而抢 CPU） |
| 雪花序号 | `SnowflakeIdGenerator` | `synchronized nextId()` | 临界区极短（几十纳秒）；每毫秒 4096 个 ID 的上限远超本平台需求 |

**ID 生成的线程安全细节**：`nextId()` 用 `synchronized`（而非 CAS 循环），因为临界区里有「读 lastTimestamp → 比较 → 更新 sequence → 更新 lastTimestamp」的多步依赖，CAS 重试代码更复杂且无收益。同一毫秒内序列耗尽时自旋等到下一毫秒，不会返回重复 ID。

### 2.7 优雅停机顺序

Spring 容器关闭时，各 `@PreDestroy` 的执行顺序**不保证**，因此每个组件都必须能独立安全收尾：

| 组件 | 停机动作 | 数据影响 |
|---|---|---|
| `TelemetryService` | `flushOnShutdown()` 刷完缓冲 | 尽力不丢最后一批；失败则丢（日志记录条数），可由车端重发补齐 |
| `MqttInboundWorker` | `running=false` + 中断线程 | 队列剩余报文丢弃并**记录条数**（车端按重发策略补投） |
| `PahoMqttClientManager` | 关闭重连调度器 → `disconnect(5s)` → `close()` | 正常断开；Broker 因 `clean_session=false` 保留 QoS 1 未确认消息 |
| `OfflineMessageQueue` | 记录剩余条数 | **进程内队列，剩余消息丢失**（已知限制，见 7.2 与 10） |

**停机时最需要注意的是刷盘与消费者中断的竞态**：`MqttInboundWorker.stop()` 中断消费线程时，若某个消费者正在 `router.route()` 内部（已入缓冲但未刷盘），`flushOnShutdown` 可能先执行完。因此停机流程的正确顺序是「先停消费者、再停 MQTT、最后刷盘」。Spring 未保证这一顺序，实践中表现为**停机瞬间可能丢 1 秒以内的遥测采样点**——这是可接受的（见 7.2 的量化说明），但必须在文档里写清，避免被误判为 bug。

---

## 3 MQTT 通道详细设计

### 3.1 Topic 模型：构造与解析严格互逆

Topic 是本平台最容易出错的地方——**拼错不会报错，只会静默丢失**。因此设计上做了三重约束：

1. 所有出站 Topic 必须经 `TopicBuilder` 构造（禁止业务代码拼串）；
2. `TopicParser` 与 `TopicBuilder` 严格互逆，由 `TopicParserAndBuilderTest` 双向验证；
3. Topic 的构成规则（作用域、方向、QoS、是否需要回复、回复类型）**全部从 `MqttType` 枚举推导**，不散落在各 Handler。

**命名规则**（4/5/6 段判定，按段数而非正则，无回溯更快）：

| 段数 | 作用域 | 形态 | 示例 |
|---|---|---|---|
| 4 | 云云（ENTERPRISE） | `uvodp/{enterpriseId}/{mqttType}/{direction}` | `uvodp/DSSAD-ENT-0001/task/up` |
| 5 | 车云（CAR） | `uvodp/{vehicleType}/{vin}/{mqttType}/{direction}` | `uvodp/low_speed/LSV0001/accident/up` |
| 6 | 车云带版本 | `uvodp/{vehicleType}/{vin}/{mqttType}/{version}/{direction}` | `uvodp/low_speed/LSV0001/someType/v1/down` |

解析策略的宽容度是刻意的：**解析层宽容、校验层严格**。`vehicleType` 不做枚举校验——协议演进新增车型不应导致报文被丢弃，非法值仅告警由上层决定。

**`MqttType` 是协议单一事实来源**（18 类报文）：

| mqttType | 中文 | 作用域 | 协议方向 | QoS | 回复类型 | 补传优先级 | 文档出处 |
|---|---|---|---|---|---|---|---|
| `inh` | 车辆准静态参数信息 | CAR | UP | 1 | `inh-resp` | BASE | 5.1.4.2.1 |
| `inh-resp` | 车辆准静态参数回复 | CAR | DOWN | 1 | — | — | 5.1.5.1.1 |
| `low-state` | 车辆低频运行状态 | CAR | UP | **0** | 无 | STATE | 5.1.4.3.1 |
| `low-base` | 车辆低频基础信息 | CAR | UP | **0** | 无 | STATE | 5.1.4.3.2 |
| `groute` | 车端导航路径信息 | **ENTERPRISE** | UP | 1 | `groute-resp` | BASE | 6.1.4.2.3 |
| `groute-resp` | 导航路径回复 | **CAR** ⚠️ | DOWN | 1 | — | — | 6.1.5.1.3 |
| `accident` | 车辆事故信息 | CAR | UP | 1 | `accident-resp` | EVENT | 5.1.4.4.1 |
| `accident-resp` | 事故信息回复 | CAR | DOWN | 1 | — | — | 5.1.5.1.2 |
| `error-report` | 车辆故障信息 | CAR | UP | 1 | `error-report-resp` | EVENT | 5.1.4.4.2 |
| `error-report-resp` | 故障信息回复 | CAR | DOWN | 1 | — | — | 5.1.5.1.3 |
| `accident-media` | 事故媒体数据请求 | CAR | **DOWN** | 1 | 无 | — | 5.1.5.2.1 |
| `accident-media-resp` | 媒体请求回复 | CAR | **UP** | 1 | 无 | EVENT | 5.1.4.1.1 |
| `remote-driving` | 远程驾驶接管信息 | ENTERPRISE | UP | 1 | `remote-driving-resp` | EVENT | 6.1.4.2.1 |
| `remote-driving-resp` | 远驾接管回复 | ENTERPRISE | DOWN | 1 | — | — | 6.1.5.1.1 |
| `task` | 任务信息 | ENTERPRISE | UP | 1 | `task-resp` | EVENT | 6.1.4.2.2 |
| `task-resp` | 任务信息回复 | ENTERPRISE | DOWN | 1 | — | — | 6.1.5.1.2 |
| `mapehn` | 地图增强信息 | ENTERPRISE | **DOWN** | 1 | `mapehn-resp` | — | 6.1.5.2.1 |
| `mapehn-resp` | 地图增强回复 | ENTERPRISE | **UP** | 1 | — | BASE | 6.1.4.1.1 |

⚠️ 注意三类「方向反直觉」的报文：`accident-media`（本平台下行要求上传）、`accident-media-resp`（车端上行回复）、`mapehn-resp`（本平台上行回复）。方向由 `MqttType.naturalDirection` 唯一决定，`TopicBuilder.ackTopic` **不会写死 DOWN**——这一点在下文 3.1.3 会看到原因。

**订阅策略：3 个过滤器覆盖全部场景**（避免为每种类型单独订阅导致订阅数爆炸）：

| 过滤器 | 覆盖 |
|---|---|
| `uvodp/{enterpriseId}/+/down` | 云云下行（任务/远驾/导航回复、地图增强） |
| `uvodp/+/+/+/down` | 车云下行（事故媒体请求、各类回复） |
| `uvodp/+/+/+/up` | 车云上行（**仅通道 B 开启**，由 `dssad.mqtt.subscribe-car-uplink` 控制） |

**为什么 `subscribe-car-uplink` 是可配的**：通道 A（车端直连监管平台）下，本平台不应该收到车端上行报文；通道 B（企业自建网关）下必须收到。用一个开关区分两种部署形态，而不是写两套代码。

#### 3.1.1 已知协议缺陷：groute 的作用域不对称

接口文档存在一处**请求与回复作用域不一致**：

```
请求 groute/up        ：uvodp/{enterpriseId}/groute/up               ← 云云（4 段，无车辆身份）
回复 groute-resp/down ：uvodp/{vehicleType}/{vin}/groute-resp/down   ← 车云（5 段，需车辆身份）
```

后果很直接：**收到请求后无法单靠「镜像请求 Topic」构造回复 Topic**——请求里根本没有 `vehicleType`/`vin`。若不处理，`TopicBuilder` 会因缺字段直接抛异常，车端永远收不到 ACK 而反复重发。

本平台的兼容处理（`TopicBuilder.ackTopic`）：

```
若回复类型的作用域 == 请求的作用域  → 直接镜像请求 Topic 的身份段
若不同（即本缺陷场景）           → 回退使用「报文体内携带的 vin」
                                  车端类型报文里没有 → 取默认值 low_speed
```

**影响面有限**：`groute-resp` 只是 ACK，最坏情况是 `high_speed` 车型收不到 ACK，按其重发策略重试若干次后放弃。已登记在《接口文档》附录 C 的缺陷清单中；若监管平台确认该 Topic 应为云云作用域，只需把 `MqttType.GROUTE_RESP` 的 scope 改回 `ENTERPRISE`，`ackTopic` 无需改动。

### 3.2 连接生命周期与指数退避

```
启动 ──► dssad.mqtt.enabled?
           │
           ├─ false ──► 状态 DISCONNECTED，平台以「无 Broker 模式」运行
           │             （REST 接口全可用，报文可用 /api/v1/simulator 注入，
           │               下发指令进离线队列）——「克隆即跑」的关键
           │
           └─ true ───► connect()
                         ├─ resolvePassword(clientId)
                         │    └─ RegulatoryGateway.fetchMqttPassword
                         │       （secret = SHA256(clientId + "CMAT")，缓存至过期前 5 分钟）
                         │       失败 → 回落静态配置，并告警
                         ├─ MqttAsyncClient(brokerUrl, clientId, MemoryPersistence)
                         ├─ options: keepAlive=60, cleanSession=false,
                         │           automaticReconnect=false（见 A-08）
                         └─ connect().waitForCompletion(30s)
                              ├─ 成功 ──► connectComplete 回调
                              │            ├─ 重置退避计数
                              │            ├─ subscribeAll()（3 个过滤器，单个失败不影响其他）
                              │            └─ flushOffline(500)  ← 补传离线积压
                              └─ 失败 ──► scheduleReconnect()
```

**退避序列**：首次立即（delay=0），之后 `initialBackoff × multiplier^(n-1)`，上限 60s：

| 尝试 | 1 | 2 | 3 | 4 | 5 | 6 | ≥7 |
|---|---|---|---|---|---|---|---|
| 延迟 | 0ms | 2s | 4s | 8s | 16s | 32s | 60s（封顶） |

**为什么不用 Paho 自带的 `automaticReconnect`**（决策 A-08）：

1. 自带策略的退避序列**不可配置**（固定为 1s 起步、每次翻倍、上限 2 分钟）；
2. 更致命的是：**无法在重连成功回调里插入「订阅恢复 + 离线补传」**。Paho 重建连接后不会自动重新订阅——若没有 `connectComplete` 回调，重连后平台会「连着但收不到任何报文」，这是最隐蔽的故障形态。

`clean_session=false` 的意义：Broker 会为 `clientId` 保留会话，QoS 1 的未确认消息在断线期间由 Broker 持有，重连后继续投递。这是「服务重启后不丢报文」的前提。

**凭据缓存与刷新**（`RegulatoryGateway`）：

| 凭据 | 缓存键 | TTL 计算 | 提前刷新 |
|---|---|---|---|
| MQTT 密码 | `regulatory:mqtt-password` | 响应 `expiredAt` − now − 5min | 5 分钟 |
| 平台 token | `regulatory:access-token` | 同上 | 5 分钟 |

提前 5 分钟刷新是为了避免「边界上用到刚过期的凭据」；`expiredAt` 缺失或非法时回落到 30 分钟固定 TTL。

### 3.3 报文处理流水线（8 步）

`MqttMessageRouter.route()` 是所有报文进入业务世界的**唯一入口**。把横切逻辑收敛在此，是保证「所有类型行为一致」的关键：

```
入站报文（来自 InboundMessageQueue）
  │
  ├─ 1. 解析 Topic ──────────► 失败：留痕 INVALID，丢弃（不回 ACK，因为不知道该回到哪）
  │
  ├─ 2. 若是回复类下行（*-resp/down）
  │       ├─ res=1 → PendingAckRegistry.complete(ackMsgId)：停止重发
  │       └─ res=2/3 → markInvalid(ackMsgId)：切换为「异常重发」策略（3s×5）
  │
  ├─ 3. 查找处理器
  │       └─ 无处理器 → 上行且协议规定需回复时回 res=3（不支持）；留痕 UNSUPPORTED
  │
  ├─ 4. 通用协议校验
  │       ├─ 必须是合法 JSON 对象
  │       ├─ 上行非回复类报文必须有 msgId
  │       └─ 车云必须有 vin；云云必须有 enterpriseId 或 vin
  │       └─ 失败 → 回 res=2；留痕 INVALID
  │
  ├─ 5. 幂等去重（仅上行业务报文）
  │       └─ (senderId, msgId) 已存在 → **照常回 res=1**，跳过业务处理；留痕 DUPLICATE
  │
  ├─ 6. 执行业务处理器
  │       ├─ BizException → res=2（业务校验失败）
  │       └─ RuntimeException → res=2（未知异常，打 error 堆栈）
  │
  ├─ 7. 回复 ACK（仅「上行 + 已知类型 + 协议规定需回复」）
  │
  └─ 8. 留痕（报文 + 处理结果 + 耗时 + 时钟偏差）
          └─ 耗时 > 1000ms 额外打 warn
```

**三个值得单独说明的设计点**：

**① 幂等重复报文为什么「照常回 ACK」**

对端重发的原因**正是没收到 ACK**。如果重复报文不回 ACK，对端会一直重发到上限才放弃——既浪费带宽，也让监控上的「异常重发」指标失真（真实的异常被重复报文淹没）。回一次 ACK 是成本最低的收敛方式。

**② 去重键为什么必须带 senderId**

```java
String key = "dedup:" + senderId + ':' + msgId;
```

车端若用简单计数或时间戳生成 `msgId`，**不同车辆的 msgId 会相同**。只按 msgId 去重会把他车的数据误判为重复而丢弃——这类缺陷在单车联调时完全看不出来，多车接入才爆发。去重窗口 10 分钟，远大于协议规定的最大重发周期（3s × 5 次 = 15 秒）。

**③ 留痕的 `clockSkewMs` 字段**

```java
Long payloadTs = envelope.longValue("timestamp");
entity.setClockSkewMs(payloadTs == null ? null : payloadTs - envelope.receivedAt());
```

即「报文内时间戳 − 平台接收时间」。这个字段的价值在于：**车端时钟漂移会直接破坏轨迹回放准确性**，而且它不会有任何报错——轨迹看起来正常，只是整体错位。运维页按 `clockSkewMs` 排序即可发现未同步 NTP 的车。这是实际联调中最常见的隐性缺陷。

**无处理器的类型怎么处理**：`replyUnsupported` 只在「上行」时回复，且仅当协议为该类型规定了回复主题。对不存在的主题发消息只会产生无意义的错误日志。

### 3.4 应用层 ACK 与重发状态机

**为什么 QoS 1 还不够**：QoS 1 只保证报文到达 Broker，**不保证对端业务处理成功**。协议 5.1.4.1 / 8.1 明确要求下游在 `res != 1` 时间隔重发。

`PendingAckRegistry` 实现两张重发策略：

| 策略 | 触发条件 | 间隔 | 最大次数 | 依据 |
|---|---|---|---|---|
| `CONFIRM` | 对端**完全没回复**（超时未确认） | 5s | 3 | 文档 5.1.4.1 |
| `INVALID` | 对端回复 `res=2/3`（明确异常） | 3s | 5 | 文档 8.1 |

状态机：

```
                    register(msgId, topic, payload, qos, priority)
                                    │
                                    ▼
                            ┌───────────────┐
                            │CONFIRM 等待中  │  nextAttemptAt = now + 5s
                            └───┬───────┬───┘
                                │       │
              收到 res=2/3       │       │  收到 res=1
              markInvalid        │       │  complete()
                                ▼       ▼
                    ┌────────────────┐  ┌──────────┐
                    │INVALID 等待中   │  │ 已移除    │
                    │next = now + 3s │  │（收敛）   │
                    └───┬────────┬───┘  └──────────┘
                        │        │
                        │        │ 收到 res=1 → complete()
                        ▼        ▼
              【重发调度器 tick】         【收敛】
              due() 取出到期项
                ├─ publish 失败 → 退回离线队列，**不消耗重发次数**
                └─ publish 成功 → advance()
                     └─ attempts+1 > maxAttempts ?
                          ├─ 否：更新 nextAttemptAt，继续等待
                          └─ 是：移除并打 **error** 日志（需人工排查对端）
```

**三个设计细节**：

1. **永远只存一份报文快照**：`register` 时就 `JsonUtils.toJson(payload)` 序列化一次，重发时直接复用。避免每次重发都重新序列化（1Hz 高频路径上这是真实开销）。
2. **`advance` 与 `due` 分离**：`due()` 只读不写，`advance()` 才推进状态。这样调度器可以先判断 `publish` 结果，失败时**不消耗次数**——否则网络瞬断会把 3 次机会白白烧掉。
3. **`replace` 而非 `put`**：`advance` 用 `registry.replace(msgId, newPending)`。如果并发场景下该 msgId 已被 `complete` 移除，`replace` 会静默失败（正确行为），而 `put` 会**把已确认的报文重新插回登记表**，导致已收敛的报文被再次重发。

**ACK 报文自身不登记重发**（`MqttCommandService.replyAck`）：

```java
// ACK 报文本身不再登记重发（否则会形成 ACK 套 ACK 的无限递归）：
// 若 ACK 丢失，由对端按其重发策略再次投递原报文，届时平台会再回一次 ACK，
// 这是协议自带的收敛机制。
```

### 3.5 离线队列与优先级配额

协议 8.4 要求「重连后优先上传：**事件数据 > 基础数据 > 状态数据**」。`OfflineMessageQueue` 用**二叉堆 + 淘汰规则**实现：

| 优先级 | 序号 | 包含报文类型 |
|---|---|---|
| EVENT | 0 | 事故、故障、远驾接管、任务、媒体请求回复 |
| BASE | 1 | 准静态参数、导航路径、地图增强回复 |
| STATE | 2 | 低频运行状态、低频基础信息（1s 一条，量最大） |

**容量满时的判定规则**（这是本类最需要仔细读的一段）：

```
if (队列已满) {
    worst = 堆内优先级最低的那条
    if (新消息.priority.order <= worst.priority.order) {
        // 新消息更重要或同级 → 淘汰 worst，收下新消息
        // 同级淘汰等价于 FIFO 轮转：对 1Hz 状态数据而言，保留「最新位置」比保留最旧的更有价值
        remove(worst); droppedByPressure++;
        offer(新消息);
    } else {
        // 新消息优先级更低 → 直接丢弃新消息，保护队列里的高价值数据
        droppedLowPriority++;
        return true;   // 告知调用方「本次消息被拒收」
    }
}
```

**为什么必须区分两个丢弃计数器**：

| 计数器 | 含义 | 运维含义 |
|---|---|---|
| `droppedByPressure` | 队列满且新消息更值得保留 → 淘汰了旧消息 | 队列容量不足，考虑调大 `offline-cache-size` |
| `droppedLowPriority` | 队列满且新消息不重要 → 拒收新消息 | 正常行为（状态报文被主动让路），无需处理 |

若只用一个计数器，运维看到数字增长时无法判断该不该介入。

**比较符方向是这类实现最容易写反的地方**：`UploadPriority.order` 越小优先级越高，所以「更重要」对应 `<=`。写反会导致**事故数据被 1Hz 状态报文挤掉**，且这种错误在联调前不会被发现（队列从不满）。因此 `MqttReliabilityTest.OfflineQueue` 逐条钉死了这些场景。

**已声明的限制**：本实现是**进程内队列**。跨实例或跨重启不丢需要把 `QueuedMessage` 序列化到 Redis List 或本地 WAL。当前不做的理由：MQTT 断线通常是短暂网络抖动，且 `clean_session=false` 已由 Broker 承担了 QoS 1 消息的持久化；进程重启时丢的离线队列只影响「重启前恰好未发出的下行指令」，而这类指令（任务/远驾）在业务上有人工重发路径。详见第 10 章。

**补传速率控制**：`MqttAckRetryScheduler` 每秒补传 20 条（`FLUSH_PER_TICK`），而不是重连瞬间全部倒出——因为对端限流是 10 条/秒·车（协议 9.1），瞬间几千条会触发对端限流反而更慢。重连成功时额外批量补传 500 条（`FLUSH_BATCH_ON_CONNECT`），这个较大的批量用于快速清空积压。

### 3.6 报文 DTO 的组织方式

| 文件 | 内容 | 约定 |
|---|---|---|
| `CarUpMessages` | 车端上行 | 全部为 `record`，字段名与协议 JSON **完全一致**（含 `msgcount` 这种小写拼写），避免加 `@JsonProperty` 增加噪音 |
| `EnterpriseUpMessages` | 云云上行 | 同上；内含 `RemoteDriving` / `Task` / `Groute` / `MapehnResp` |
| `DownMessages` | 下行 | `AccidentMediaRequest` / `Mapehn` |
| `AckMessage` | ACK 回复 | 由 `AckMessage.reply(request, msgId, vin, code)` 构造，**自动镜像请求身份** |

**DTO 用 record 的理由**：协议报文是纯数据载体，record 自带不可变性、`equals`/`hashCode`、构造器即文档。且 record 的组件名就是访问器名（`payload.vin()`），与 JSON 字段名一致时可被 Jackson 直接绑定，无需注解。

**Jackson 的两处全局配置**（`application.yml`，与协议约定强相关）：

```yaml
spring.jackson:
  serialization:
    write-dates-as-timestamps: true    # Instant → 13 位毫秒数字（协议 1.4 要求）
    fail-on-empty-beans: false
  deserialization:
    fail-on-unknown-properties: false  # 对端新增字段不能让报文整体失败
  default-property-inclusion: non_null
  time-zone: Asia/Shanghai
```

`fail-on-unknown-properties: false` 是**面向协议演进的必需配置**：监管平台新增字段时，本平台不应因此拒绝整条报文。

**注意 `LocalDate` 的序列化陷阱**：`write-dates-as-timestamps=true` 会把 `LocalDate` 也变成数组 `[年, 月, 日]`（而非 `"2026-09-23"`）。这是本平台对前端暴露的一个**非直觉契约**，已在《接口文档》第 2 章与前端 `utils/format.ts` 的 `parseTime` 中同时做了兼容处理。

---

## 4 业务模块详细设计

### 4.1 车辆遥测（low-base / low-state）

**入口**：`LowBaseMessageHandler` / `LowStateMessageHandler` → `TelemetryService`

| 维度 | low-base（5.1.4.3.2） | low-state（5.1.4.3.1） |
|---|---|---|
| 上报频率 | 1 Hz | 1/60 Hz |
| QoS | 0 | 0 |
| 是否回复 | 否 | 否 |
| 数据去向 | ① 轨迹表缓冲（`trackBuffer`）② 实时位置缓存 ③ 车辆心跳 | ① 状态流水缓冲（`stateBuffer`）② 最新状态缓存 |
| 承载字段 | 经纬度、高程、速度、加速度、航向、驾驶模式 | GNSS 状态、总重、胎压、续航、里程、SOC、温度、充电状态 |

**处理路径**（`onLowBase`，刻意不加 `@Transactional`）：

```
消费线程
 ├─ payload.timestamp() 为空 → 用平台时间兜底（车端可能不上报时间）
 ├─ trackBuffer.size() >= 100000 ?
 │    是 → droppedTrackPoints++ ，每 10000 条打一次 error（避免日志刷屏）
 │    否 → 组装 VehicleTrackPoint（主键用雪花 ID）→ offer
 ├─ vehicleService.touchHeartbeat(vin, vehicleType)      ← 见 4.2，高频路径专门优化
 └─ stateStore.put("vehicle:position:" + vin, JSON, 30min)
```

**实时位置为什么要进缓存而不是查库**：列表页与地图页需要「每台车的当前位置」。若查库，SQL 是「按 vin 分组取每个 vin 的最新一条」——在 8640 万行/天的表上这是灾难。写入时顺手放一份到 `StateStore`（TTL 30 分钟），读取时 O(1)。TTL 取 30 分钟而非 1 秒，是因为要容忍短暂断连——列表页显示「30 分钟前的最后位置」比显示空白有用。

**温度字段的偏移量处理**：协议规定 `temperature` 是整数且**偏移 100**（真实 25.5℃ 上报 125）。本平台在实体侧存原始值，但在 DTO 侧同时提供 `temperatureCelsius`（已减 100 的真值）。**从契约层消除漏减的可能**——这是「静默出错」的典型场景：漏减不会报错，只是仪表盘上显示 125℃。

### 4.2 车辆档案与在线状态判定

**自动建档策略**（`VehicleService.ensureVehicle`）：协议**没有定义车辆注册接口**（车端连上 Broker 就直接上报）。因此平台在首次收到某 VIN 的报文时自动建档，后续报文只做更新。

这个选择的价值：避免「报文先到、档案后建」导致入库失败，符合监管场景「数据不能因缺档案而丢失」的要求。车牌、车型等企业自有信息由管理端补充。

**心跳处理的性能优化**（`touchHeartbeat`，这是平台最高频的写操作）：

朴素实现是「`findById` 判断存在 → 加载实体 → 改字段 → 保存」，每车每秒 2 条 SQL 且更新全列。三处优化：

| 优化 | 做法 | 效果 |
|---|---|---|
| 存在性短路 | 建成后 `stateStore.setIfAbsent("vehicle:exists:" + vin, 10min)` | 10 分钟内不再查库 |
| 单条 UPDATE | `vehicleRepository.touchOnline(vin, now)` 只写 3 列 | 不加载实体、不做脏检查写回 |
| 上下线读缓存 | `stateStore.get("vehicle:lastbase:" + vin)` | 不为比较时间而回表 |

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("update Vehicle v set v.online = true, v.lastBaseAt = :at, v.updatedAt = :at where v.vin = :vin")
int touchOnline(@Param("vin") String vin, @Param("at") Instant at);
```

`clearAutomatically = true` 是必须的：`@Modifying` 直接执行 UPDATE 绕过了持久化上下文，若不清空一级缓存，同一事务内后续 `findById` 会读到**旧的 online 值**。

**离线判定**：阈值 2 分钟（`OFFLINE_THRESHOLD`）。判定不在心跳时做（那需要回表比较时间），而是由**批量 UPDATE** 统一处理：

```java
@Query("update Vehicle v set v.online = false where v.online = true and v.lastBaseAt < :deadline")
int markOfflineBefore(@Param("deadline") Instant deadline);
```

一条 SQL 处理所有超时车辆，而不是每台车一次 UPDATE。该方法现由 `VehicleOnlineSweeper` 每 30 秒调度一次（v1.0.3 接线，多实例下带 `DistributedTaskLock` 互斥），车辆离线状态最迟约 2.5 分钟回落；执行统计暴露在 `/api/v1/monitor/mqtt` 的 `vehicleOnlineSweep` 节点。

**计数自增用 SQL 而非读改写**：

```java
@Query("update Vehicle v set v.accidentCount = v.accidentCount + 1 where v.vin = :vin")
int increaseAccidentCount(@Param("vin") String vin);
```

读改写（`v.setAccidentCount(v.getAccidentCount() + 1)`）在两车同时上报时会丢更新；SQL 自增由数据库行锁保证正确。

**列表查询用 `Specification` 而非 8 个 finder**：前端是「关键词 + 类型 + 在线状态」的自由组合，组合数会爆炸。`Specification` 用 `cb.or/cb.and` 动态拼条件，一个方法覆盖全部组合。

### 4.3 事故取证闭环（核心业务）

这是本平台**最完整也最复杂**的一条链路，涉及 MQTT 上行、MQTT 下行、HTTP 上传三种协议与四个参与方。

#### 4.3.1 状态机

```
                    ┌──────────────────────────────────────────────┐
                    │  车端上报 accident/up                         │
                    ▼                                              │
            ┌───────────────┐                                      │
       ┌───►│   REPORTED    │  仅收到结构化数据，尚未请求媒体         │
       │    └───────┬───────┘                                      │
       │            │ 平台立即下发 accident-media/down（前/右/后/左）│
       │            │ 记录 mediaRequestMsgId ← 授权凭据              │
       │            ▼                                              │
       │    ┌───────────────┐       车端回 res!=1                  │
       │    │MEDIA_REQUESTED│──────────────►┌──────────────┐       │
       │    └───────┬───────┘               │ MEDIA_FAILED │       │
       │            │ 收到 accident-media-resp（按 ackMsgId 配对）   │
       │            │ res=1                                        │
       │            ▼                                              │
       │    ┌───────────────┐                                      │
       │    │MEDIA_ACCEPTED │  车端已受理，等待 HTTP 上传            │
       │    └───────┬───────┘                                      │
       │            │ 每次 HTTP 上传归档后重算「已收方向数」          │
       │            │                                              │
       │            ├── distinctDirections < mediaExpectedCount ───┘（继续等待其他方向）
       │            │
       │            └── distinctDirections >= mediaExpectedCount
       │                    ▼
       │            ┌───────────────┐
       └────────────│MEDIA_ARCHIVED │  取证完成
       重复上报      └───────────────┘
       （幂等）
```

**`mediaExpectedCount` 必须落库，不能写死 4**：

```java
/**
 * 本次媒体请求要求的方向数量（用于判断「是否已收齐」）。
 *
 * <p>必须落库而不是写死 4：监管平台可能只请求 1~2 个方向（例如仅前向），
 * 若按固定 4 判断，事故事件将永远停在 MEDIA_ACCEPTED，前端一直显示「取证中」。
 */
@Column(name = "media_expected_count", nullable = false)
private Integer mediaExpectedCount = 0;
```

这是从「4 方向全量要求」推广到「按请求实际方向数判定」的关键——写死 4 会导致部分取证事件永久悬挂。

**「是否收齐」按去重方向数而非文件数**：同一方向重复上传不应算作收齐，因此用 `countDistinctDirectionByEventId` 而非 `countByEventId`。对应的测试用例：`PlatformEndToEndTest`「同一方向重复上传不重复建档，且不会被误判为收齐」。

#### 4.3.2 完整时序

```
车端                本平台 MQTT            本平台 Service          车端 HTTP        本平台存储
 │                      │                       │                     │               │
 ├─ accident/up ───────►│                       │                     │               │
 │                      ├─ 路由 8 步流水线       │                     │               │
 │                      ├─ 去重(senderId,msgId)  │                     │               │
 │                      ├──────────────────────►│                     │               │
 │                      │                       ├─ onAccident()       │               │
 │                      │                       │  · eventId 幂等      │               │
 │                      │                       │  · 落库 REPORTED     │               │
 │                      │                       │  · 车辆计数 +1       │               │
 │                      │                       │                     │               │
 │                      │   event.getMediaStatus == REPORTED ?        │               │
 │                      │      是 → 立即下发媒体请求                   │               │
 │◄─ accident-media/down┤◄──────────────────────┤                     │               │
 │  (前/右/后/左 4 方向) │  register(msgId→待确认) │                     │               │
 │                      │                       ├─ markMediaRequested│               │
 │                      │                       │  (存 msgId,expected=4)             │
 │                      │                       │                     │               │
 │                      │◄─ res=1 ACK ──────────┤                     │               │
 ├─ accident-resp/up ──►│                       │                     │               │
 │                      ├─ 幂等重复？否          │                     │               │
 │                      │                       │                     │               │
 ├─ accident-media-resp/up ─►│                  │                     │               │
 │  { ackMsgId: <上文的>, res: "1" }            │                     │               │
 │                      ├──────────────────────►│                     │               │
 │                      │                       ├─ onAccidentMediaResp│               │
 │                      │                       │  · 按 ackMsgId 反查事件             │
 │                      │                       │  · 状态 → MEDIA_ACCEPTED           │
 │                      │                       │                     │               │
 ├─ POST /enterprise/api/v1/accident/media (multipart) ──────────────►│               │
 │   vin + ackMsgId + eventId + cameraDirection + file               │               │
 │                      │        （签名过滤器白名单：multipart 不做 HMAC）              │
 │                      │                       ├─ ingest()           │               │
 │                      │                       │  · 校验 ackMsgId == mediaRequestMsgId（授权）
 │                      │                       │  · 校验 vin == event.vin             │
 │                      │                       │  · 校验状态非 MEDIA_FAILED          │
 │                      │                       │  · 幂等：同 eventId+方向 已有 → 直接返回
 │                      │                       ├─ storeLocal() ────►│               │
 │                      │                       │   {root}/{kind}/{yyyyMMdd}/{vin}/{dir}/{uuid}.mp4
 │                      │                       ├─ archiveAccidentMedia                │
 │                      │                       │  · 落 MediaAsset                   │
 │                      │                       │  · 重算 distinctDirections          │
 │                      │                       │  · 收齐 → MEDIA_ARCHIVED            │
 │◄──────────────────── 200 {code:"0000"} ────────────────────────────────────────────┤
```

#### 4.3.3 安全设计：为什么用「业务态授权」

`EnterpriseSignatureFilter` 明确**把 multipart 请求白名单掉**：

```java
@Override
protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!request.getRequestURI().startsWith(PATH_PREFIX)) return true;
    String contentType = request.getContentType();
    return contentType != null && contentType.startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
}
```

原因：body 是可达 200MB 的二进制，把它读进内存算 HMAC 会带来严重的内存与延迟问题（一次上传要占 200MB 堆，10 个并发上传直接 OOM）。

替代方案是**三重业务态校验**（`AccidentMediaService.ingest`）：

| 校验 | 代码 | 防的是什么 |
|---|---|---|
| 事件必须有下发的 `mediaRequestMsgId` | `if (!StringUtils.hasText(event.getMediaRequestMsgId())) throw UNAUTHORIZED` | 平台没请求过就上传 → 拒绝（否则任何车都能凭空灌视频污染证据链） |
| `ackMsgId` 必须与记录一致 | `if (!event.getMediaRequestMsgId().equals(ackMsgId)) throw UNAUTHORIZED` | 拿别的事件编号冒用 |
| `vin` 必须与事件所属车辆一致 | `if (!event.getVin().equals(vin)) throw UNAUTHORIZED` | 拿别的车的事故编号上传 |

有了这三条，**车端只能对平台已经请求过的那次事件、那个方向上传视频**，形成闭环。对应测试：`PlatformEndToEndTest`「ackMsgId 与平台下发的请求不匹配时拒绝建档（越权上传防护）」。

#### 4.3.4 幂等与容错

| 场景 | 行为 | 理由 |
|---|---|---|
| 同一 `eventId` 重复上报 accident | 返回已有记录，**不重复计数** | 车端 QoS 1 重发是常态；计数加两遍会让统计失真 |
| 同一 `eventId + 方向` 重复上传 | 返回已有归档记录，不重复落盘 | 车端网络抖动重试 |
| 媒体请求下发失败 | 报文进离线队列；事件停在 `REPORTED` | 可由 `EventService.pendingAccidents` 巡检重新发现（**不丢**） |
| 车端拒绝（`res != 1`） | 状态 → `MEDIA_FAILED`，后续上传被拒 | 明确失败优于静默等待；需人工介入（协议要求车端本地至少存 10 天） |

`pendingAccidents` 查询未归档且早于某时刻的事件，是「取证不闭环」的巡检入口：

```java
@Query(...)  // AccidentEventRepository.findUnarchivedBefore(PENDING_MEDIA_STATUSES, before, pageable)
```

### 4.4 故障上报

**入口**：`FaultReportMessageHandler` → `EventService.onFaultReport`

**数据模型：主记录 + N 条明细**（变长故障清单）：

```
t_fault_record (1)  ──►  t_fault_item (N)      ← 本项目唯一使用物理外键的关联
```

**为什么只有这一处加物理外键**：其余关联（事故→媒体、任务→轨迹点）都靠业务字段（`event_id`、`vin`）关联而不建外键，原因是**流水表加外键会让高并发批量写入相互等待**。但故障明细与父记录**同属一个报文、同一事务**，必须用外键保证不出现孤儿明细——父记录删除而明细残留会让「故障码 Top 榜」统计到不存在的数据。

**最严重等级冗余到主表**：

```java
record.setMaxSeverity(items.stream()
        .mapToInt(item -> parseSeverity(item.errorSeverity()))
        .max().orElse(0));
```

故障中心列表按严重程度排序是最高频查询。若不冗余，每次列表查询都要 `JOIN + MAX() + GROUP BY`——在故障明细表上这是昂贵的聚合。冗余一列换取列表页的索引扫描（`idx_fault_severity(max_severity, reported_at)`）。

**`error_severity` 的取值范围**：`0~7`（不是 DDL 早期注释写的 `1~5`）。`parseSeverity` 用 `try/catch NumberFormatException` 兜底为 0——脏数据不能导致整条故障上报失败。

**故障码翻译在服务层完成**：

```java
items.stream().map(i -> i.errorCode() + "(" + FaultCodeDictionary.nameOf(i.errorCode()) + ")").toList()
```

`FaultCodeDictionary` 维护 29 项故障码与分类（动力/制动/转向/感知等）。 **库内只存码值**，翻译在读取时做：字典变更（新增故障码、修正名称）不需要刷数据。

### 4.5 轨迹查询与两段式降采样

**入口**：`GET /api/v1/vehicles/{vin}/track` → `TrackQueryService.query`

单车一天 8.64 万个点。**前端地图一次性渲染数万点会直接卡死，而监管取证要求原始数据完整留存**。因此策略是「库内存全量、展示层抽稀」，且抽稀分两段：

```
query(vin, fromMs, toMs, maxPoints)
  │
  ├─ ① countByVinAndTsBetween → rawCount
  │     └─ rawCount == 0 → 直接返回空（不做无意义的后续步骤）
  │
  ├─ ② rawCount <= limit(3000) ?
  │     ├─ 是 → findTrack(全量取，不抽稀)      ← 「小范围查询逐点还原」
  │     │        （为「可能不需要的精度」付出随机 IO 不划算，但量小时保真更重要）
  │     └─ 否 → 进入降采样
  │
  ├─ ③ SQL 侧时间桶粗筛
  │     bucketMillis = alignBucket((toMs - fromMs) / limit)
  │     findTrackBucketed(vin, from, to, bucket, PageRequest(0, limit*3))
  │     → WHERE mod(ts, bucketMillis) < 1000
  │     → 把行数从几十万压到万级
  │
  ├─ ④ 内存侧 Douglas-Peucker 抽稀（容差 8.0 米）
  │     → 继续压到千级，且保证形变在容差内
  │
  └─ ⑤ 返回 TrackResult(points, rawCount, returnedCount, tolerance, ...)
```

**只用 SQL 或只用抽稀都不行**：

| 方案 | 问题 |
|---|---|
| 只用 SQL 时间桶 | 保留大量「直线上冗余点」——车速平稳时每个桶都命中，点数仍上万 |
| 只用 DP 抽稀 | 要先把几十万行读进内存，再抽稀；随机 IO 与内存峰值都无法接受 |
| **两段式** | SQL 先压数量级，抽稀再压一个数量级；代价是两处近似，用 `rawCount`/`returnedCount` 明示 |

**`alignBucket` 为什么必须对齐到 1 秒的整数倍**：

数据是 1Hz 上报（`ts` 为整秒的毫秒值）。策略 `mod(ts, bucket) < 1000` 只有在 `bucket` 是 1000 的整数倍时，才能保证每个桶**恰好命中一次**；否则会出现某些桶命中 0 次或 2 次，导致轨迹出现**空洞或抖动**。

```java
private long alignBucket(long roughBucketMillis) {
    long seconds = Math.max(1L, roughBucketMillis / 1000L);
    return seconds * 1000L;   // 向下取整到秒，宁可桶多一点也不用错位的桶
}
```

**DP 抽稀的单位一致性**（`TrackSimplifier` 中有一条长注释记录了这个坑）：

```java
// ⚠️ 纬度与经度都必须换算成「米」后再参与距离计算。
// 早期版本只把经度乘了 scaleLng、纬度直接拿「度」去算，两个坐标轴单位不一致，
// 点到线段的距离完全没有物理意义：南北方向的偏移被缩小约 11 万倍，
// 于是「向北 100 米再向东 100 米」的直角弯会被判定为共线而抽掉 ——
// 底图上的轨迹会直接拉直绕过拐弯，且因为东西向数据仍然正常，
// 这个缺陷只在南北向道路上暴露，极难在联调时发现。
double lat1 = accessor.latitude(points.get(start)) * METERS_PER_DEGREE;   // 111320
double lng1 = accessor.longitude(points.get(start)) * scaleLng;           // 111320 × cos(lat)
```

这是本项目最有价值的注释之一：**它记录了一个「看起来正常、只在特定方向暴露」的缺陷**。经度方向按 `cos(纬度)` 修正（成都 30° 约 0.866），避免高纬度地区距离被高估。

**复杂度**：平均 O(n log n)，最坏 O(n²)（单调点集）。对 8.6 万点单次请求实测 20ms 量级，故放在请求线程内同步执行。

### 4.6 运营协同：任务 / 远驾 / 导航

三个云云上行业务报文（协议 6.1.4.2）在 `OperationService` 中共享两条设计原则：

**原则一：配对语义 —— 开始/结束归并为一条生命周期记录**

```java
String taskId = ...;
Optional<TrackTask> existing = taskRepository.findByTaskId(payload.taskId());
if (isStart) {
    TaskTask task = existing.orElseGet(TrackTask::new);     // 允许重复开始（幂等）
    ...
    task.setStatus(RUNNING);
} else {
    task = existing.orElseGet(() -> {
        // 结束报文先于开始到达（MQTT QoS 1 乱序、或平台刚重启）
        TrackTask orphan = new TrackTask();
        orphan.setStatus(ORPHAN_FINISH);
        log.warn("[任务] 收到结束报文但无开始记录，登记为异常任务 taskId={} vin={}", ...);
        return orphan;
    });
    ...
}
```

**孤儿记录（`ORPHAN_FINISH`）为什么不能丢弃**：MQTT QoS 1 允许乱序；平台重启也会造成「开始报文丢失、结束报文到达」。若直接丢弃这条结束报文，**监管数据就彻底消失了**——而保留一条显式标记异常的记录，运维能查出「哪个任务号缺开始报文」，这才是可闭环的处理。

**原则二：企业侧下发必须「上报 + 落库」双写**

`OperationService.startTask`（企业侧主动发起任务）的注释说得很清楚：

```java
/**
 * <p>为什么必须同时「上报监管平台」和「本地落库」：
 * <ul>
 *   <li>上报是合规要求 —— 任务开始/结束必须让监管侧看到；</li>
 *   <li>落库是可用性要求 —— 只上报不落库时，平台自己的「任务列表」永远是空的，
 *       运维无法回答「这台车今天跑了哪几趟」，而监管侧一旦没收到报文，数据就彻底不存在。</li>
 * </ul>
 * 两条动作都在同一个事务/同一个调用内完成，避免出现「上报成功但本地无记录」的中间态。
 */
```

**远驾接管的配对方式**：协议**没有提供接管单号**，因此「结束」报文通过「该车最近一条未结束的发起记录」配对：

```java
RemoteDrivingRecord record = remoteDrivingRepository
        .findFirstByVinAndClosedFalseOrderByOccurredAtDesc(payload.vin())
        .orElseGet(() -> { /* 同样落孤儿记录，不丢弃 */ });
```

这是在没有业务主键情况下的次优解。若同一车辆短时间内连续两次接管且第一次的结束报文丢失，配对会错位——已登记在 10 章的待改进清单。

**远驾记录的合法性校验**（`pageRemoteDriving` 的两处早期缺陷）：

| 缺陷 | 症状 | 修正 |
|---|---|---|
| 「按车筛选」时 `closed` 被忽略 | 「进行中」列表里混进已结束记录 | 统一走 `search(vin, closed, from, to, pageable)` |
| `closed=true` 时走 `findAll` | 返回**全量**记录而非仅已结束 | 同上 |

且方法签名做了语义收缩：`Boolean.TRUE.equals(closed) ? TRUE : FALSE`——**未显式指定时默认只看进行中**，因为运营页最常用的是「当前有哪些车被接管」。

**导航路径（groute）**：路径点整体存 JSON + 冗余起终点与点数：

| 字段 | 用途 |
|---|---|
| `path_points` | 完整路径点 JSON（`[[lat,lng,alt,speed],...]`） |
| `point_count` | 点数（列表页展示，避免解析 JSON） |
| `start_latitude/longitude`、`end_latitude/longitude` | 起终点（列表页与筛选用） |
| `current_latitude/longitude` | 上报时的当前位置 |

**坐标数组顺序统一为 `[纬度, 经度]`**（协议规定）。这是最容易写反的地方，因此 `applyPoint` 抽成方法并写清注释：

```java
/**
 * 解析 currentPoint（协议规定：数组第一位是纬度，第二位是经度）。
 *
 * <p>这是最容易写反的地方，故抽成方法并写清注释；同时做长度与空值防护，
 * 避免脏数据导致「纬度 130、经度 22」这种看似正常实则错位的记录入库。
 */
```

写反的后果：**纬度 130 不合法但不会报错**（数据库是 `Double` 无约束），只是地图上位置完全错。长度防护（`point.size() < 2` 则跳过）同样必要。

### 4.7 地图增强：双通道接入

**为什么需要两个通道**：

| 通道 | 触发 | 实现 | 服务对象 |
|---|---|---|---|
| **推送** | 监管平台主动下发 `mapehn/down` | `MapehnDownstreamHandler` → `MapBarrierService.onPush` | 实时性 |
| **拉取** | 本平台每日 00:05 调 HTTP `/event/mapehn` | `MapBarrierService.scheduledPull` → `RegulatoryGateway.fetchTodayMapehn` | 兜底 |

**MQTT 下行不保证送达**（QoS 1 只在「已连接」时有效，断线期若 Broker 未保留会话则丢失）。因此拉取不是冗余功能，而是**必需的补偿机制**。这是「主通道 + 兜底通道」的典型模式。

**幂等键 `(barrierId, effectiveDate)`**：

```java
@Table(indexes = {
        @Index(name = "uk_barrier_id_date", columnList = "barrier_id, effective_date", unique = true),
        ...
})
```

同一天同一阻断点被推送多次（或推送与拉取都到达）时**只更新内容、不产生重复记录**，避免前端地图出现重叠图形。两级 upsert：

```java
MapBarrier barrier = barrierRepository
        .findByBarrierIdAndEffectiveDate(payload.barrierId(), today)
        .orElseGet(MapBarrier::new);
```

**`source` 字段标记来源（PUSH / PULL）**：运维可据此判断「今天这批数据是推来的还是拉来的」。若某天全部是 PULL，说明推送通道有问题——这是一个**低成本的通道健康指示器**。

**回执（ACK）的闭环**：

```
收到 mapehn/down
  → onPush() 落库（acked = false）
  → 回复 mapehn-resp/up（res=1）
  → markAcked(barrierId)（acked = true）
```

`acked` 字段让「已收到但未回执」的状态可见。**另有一个易错点**：`mapehn` 的回复方向是**上行**（`uvodp/{enterpriseId}/mapehn-resp/up`），而多数回复是下行。因此 `TopicBuilder.ackTopic` 特意从 `ackType.getNaturalDirection()` 取方向，**而非写死 DOWN**——写死会导致回复发到无人订阅的主题，监管平台收不到回复便会**持续重发 mapehn**，表现为车端被重复下发地图增强数据（详见 3.1 节代码注释）。

**区域编码的来源**：`pullToday(areaCode)` 的 areaCode 由 `System.getProperty("dssad.area-code", "")` 传入——生产环境应按车辆实际运营城市配置（这是一个待补的配置项，见第 10 章）。

### 4.8 媒体与视频

`MediaService` 承担三类职责，用**一个响应结构**统一：

| 场景 | 接口 | 返回 |
|---|---|---|
| 实时视频 | `POST /enterprise/api/v1/video/live` | 拉流地址（m3u8 / flv），**不转码不落盘** |
| 历史视频 | `POST /enterprise/api/v1/video/history` | 已归档文件的下载地址（mp4） |
| 文件落盘 | `storeLocal()` | 存储路径 + 对外 URL |

**地址与文件分离**的设计价值：实时视频只返回地址（由 SRS 提供 HLS/HTTP-FLV），历史视频返回已归档文件。两者共用一个响应结构，**前端无需区分处理**。

**历史视频的区间重叠语义**（这是一个容易漏的点）：

```java
List<MediaAsset> assets = mediaAssetRepository
        .findByVinAndCameraDirectionAndStartTimeLessThanEqualAndEndTimeGreaterThanEqualOrderByStartTimeAsc(
                vin, direction, queryEnd, queryStart);
```

查询条件用「**重叠**」而非「包含」：`asset.start <= queryEnd AND asset.end >= queryStart`。原因是录像切片与用户选的时间区间几乎不可能完全对齐——用户选 `00:00~24:00` 却查不到 `00:00~01:00` 的切片，这个 bug 的成因就是用了包含语义。

**上传参数校验的三层**（`storeLocal`）：

| 层 | 校验 | 错误码 |
|---|---|---|
| 内容 | `content == null \|\| length == 0` → 拒绝 | `PARAM_INVALID` |
| 大小 | `length > maxBytes`（200MB，协议 9.2）→ 拒绝 | `PAYLOAD_TOO_LARGE` |
| 扩展名 | 白名单 `.mp4/.ts/.m3u8/.flv/.jpg/.png`，其他 → `.bin` | 静默规范化 |

**扩展名白名单化是安全措施**（不只是整洁）：避免路径穿越或落出可执行文件。配合 `sanitize()` 净化目录段：

```java
/** 目录名净化：只保留字母数字和 - _，防止路径穿越（如 ../../）。 */
private String sanitize(String segment) {
    if (segment == null || segment.isBlank()) return "unknown";
    String safe = segment.replaceAll("[^A-Za-z0-9_-]", "");
    return safe.isEmpty() ? "unknown" : safe;
}
```

**目录结构 `{root}/{kind}/{yyyyMMdd}/{vin}/{direction}/{uuid}.{ext}`**：

- 按天分目录 → 便于按日期归档/清理（与保留策略配合）
- 按 VIN 分目录 → 便于单车导出取证材料
- 文件名用 UUID → 避免同名覆盖与信息泄露（原始文件名可能含车牌等敏感信息）

**查询跨度限制**：单次历史视频查询最大 168 小时（7 天，`api.history-video-max-span-hours`），超限报 `PARAM_INVALID`。避免一次请求扫描整月录像。

### 4.9 远驾舱 LRU 分配

**业务背景**：交巡警或监管平台发现无人车异常时可发起远程接管。企业云端收到请求后必须指派「远驾舱 + 驾驶员」，并同步上报监管平台。

**分配策略**：最久未使用（LRU）

```java
private AppProperties.RemoteDriving.Cockpit allocate() {
    List<Cockpit> pool = pool();
    Cockpit selected = pool.stream()
            .min(Comparator.comparingLong(this::lastUsedAt))   // 取「最久没被用过」的
            .orElse(FALLBACK_COCKPIT);
    stateStore.put(ALLOCATION_KEY_PREFIX + selected.cockpitId(), String.valueOf(now), Duration.ofDays(7));
    return selected;
}
```

| 设计点 | 理由 |
|---|---|
| LRU 而非轮询 | 避免连续把任务压给同一个舱（驾驶员疲劳风险）；无需引入负载均衡器 |
| 分配时间存 `StateStore` | 舱位数量通常个位数，N 次 GET 开销可忽略；TTL 7 天足够覆盖运营周期 |
| `VEHICLE_COCKPIT_KEY_PREFIX` 记录车→舱绑定 | 供 `endTakeover` 配对，使「结束」报文能带上原舱号 |

**兜底资源（`FALLBACK_COCKPIT`）**：

```java
if (cockpits == null || cockpits.length == 0) {
    log.error("[远驾] 未配置远驾舱资源池（dssad.remote-driving.cockpits），已使用兜底资源返回，"
            + "请在配置中补全以避免监管侧拿到占位数据");
    return List.of(FALLBACK_COCKPIT);
}
```

工程取向是「**不让配置缺失导致接口不可用**」：返回结构完整的响应（监管侧能正常解析），同时用 `error` 级日志提示运维。代价是监管侧可能拿到占位数据——所以日志级别必须是 error 而不是 warn。

**双重落库**（`startTakeover`）：

```java
String msgId = mqttCommandService.reportRemoteDriving(payload);
// 同时在本平台留下接管记录：远驾接管属于强监管行为，记录不能依赖对端 ACK 才产生
// （监管平台抖动、resp 丢失时，企业侧仍需能自证「何时由谁接管了哪台车」）。
operationService.onRemoteDriving(payload);
```

远驾接管是强监管行为，**本平台必须能自证「何时由谁接管了哪台车」**，因此落库不能依赖对端 ACK。

**发起人取值校验**：`starter` 只能是 `1`（交巡警）或 `2`（监管平台）：

```java
public void validateStarter(String starter) {
    if (!"1".equals(starter) && !"2".equals(starter)) {
        throw new BizException(ErrorCode.PARAM_INVALID, "starter 取值必须为 1（交巡警）或 2（监管平台）");
    }
}
```

**驾驶证号的打码处理**：配置中的 `identityNumber` / `driverLicenseNumber` 以掩码形式存储（如 `5101**********1234`）——**原始证件号不进入平台存储**。这是数据最小化原则的落地（前端 `RemoteDrivingView` 也做了展示层二次打码）。

### 4.10 仪表盘与统计

**四个只读接口**（`DashboardController`）：

| 接口 | 数据 | 缓存 TTL | 口径 |
|---|---|---|---|
| `/dashboard/overview` | 车辆总数、在线数、今日事故/故障数、阻断点数 | **5s** | 近实时 |
| `/dashboard/trend?days=N` | 按天的事故数与故障数折线 | 60s | 可容忍一分钟 |
| `/dashboard/fault-categories` | 故障按分类聚合 | 60s | 同上 |
| `/dashboard/fault-vehicles` | 故障车 Top 榜 | 60s | 同上 |

**TTL 差异化是刻意的**（决策 A-05）：大屏总览要近实时（5s，与前端轮询周期 10s 匹配），趋势与榜单可容忍 1 分钟。统一 TTL 只能迁就最短的，等于白白丢掉命中率。

**前端轮询周期必须 ≥ 缓存 TTL**：前端 `VITE_POLL_INTERVAL_MS=10000`（10 秒），大于 overview 的 5 秒 TTL。若轮询快于 TTL，用户看到的是缓存值而产生了「刷新生效」的错觉，且白白浪费请求。

**严重故障的口径统一为「等级 ≥ 6」**：大屏、故障列表筛选、Top 榜都使用同一口径。口径散落各处会导致「大屏说 3 起、列表筛出 5 起」这类无法解释的差异。

**早期缺陷记录**：`DashboardController.trend()` 的注释曾声称返回「任务数、在线车辆数」，**实际只返回 `accident` 与 `fault`**。已修正注释，并登记在《接口文档》附录 C 的 I 系列（内部不一致清单）中。启示是：**注释与实现不符比没有注释更危险**——它会让人按错误的前提排查问题。

---

## 5 安全设计

### 5.1 三套鉴权矩阵

平台共有三个互不相同的鉴权面，**用三套机制而非一套**，是因为它们的约束条件差异很大：

| 维度 | ① 管理 API | ② 对外 API | ③ 媒体上传 |
|---|---|---|---|
| 路径 | `/api/v1/**` | `/enterprise/api/v1/**` | 同上（multipart） |
| 调用方 | 浏览器控制台 | 监管平台 | 车端 / 网关 |
| 机制 | `X-Token` 头 + 服务端会话 | HMAC-SHA256 + 时间窗 + Nonce | 业务态授权（ackMsgId） |
| 实现 | `AdminTokenInterceptor` | `EnterpriseSignatureFilter` | `AccidentMediaService.ingest` |
| 令牌形态 | UUID v4（122 位随机） | 每次请求签名 | 无需令牌 |
| 有效期 | 12 小时 | 单次（5 分钟时间窗 + Nonce 一次性） | 事件级 |
| 为什么不用签名 | 浏览器持密钥不现实 | — | body 是 200MB 二进制，读进内存算 HMAC 会 OOM |
| 白名单 | `auth/login`、`monitor/health-check`、`simulator/**`、`error`、OPTIONS | multipart 请求 | — |

**拦截器 vs 过滤器的选择**（不是随便选的）：

- 管理 API 用 `HandlerInterceptor`：不需要读请求体，且需要在 Spring MVC 上下文中工作；
- 对外 API 用 `Filter` 且 `@Order(HIGHEST_PRECEDENCE + 10)`：**必须在读取请求体之前完成请求体缓存包装**（`CachedBodyRequestWrapper`），否则下游 Controller 会读到已被消费的空 body。这是「过滤器而非拦截器」的硬性原因。

### 5.2 HMAC 签名与防重放

**签名方案**（`EnterpriseSignatureFilter`）：

```
请求头：
  X-Enterprise-Id : 企业 ID（准入平台分配）
  X-Timestamp     : 13 位毫秒时间戳
  X-Nonce         : 一次性随机串（UUID）
  X-Sign          : HMAC-SHA256(secret, timestamp + "." + nonce + "." + body) 的小写十六进制

secret = SHA256(enterpriseId + secretSalt)     ← salt = "CMAT"（与文档 5.2.2.1.1 的密钥派生一致）
```

**三重防护**（缺一不可）：

| 防护 | 实现 | 拒绝条件 | 错误码 |
|---|---|---|---|
| 时间窗 | `Math.abs(now - requestTime) > 5min` | 超出 5 分钟 | `SIGN_INVALID` |
| Nonce 一次性 | `stateStore.setIfAbsent("auth:nonce:{eid}:{nonce}", 10min)` | 同一 nonce 第二次出现 | `SIGN_INVALID` |
| 常量时间比较 | `MessageDigest.isEqual(expected, signature)` | 签名不匹配 | `SIGN_INVALID` |

**三重防护各自防什么**：

1. **时间窗**限制抓包后重放的有效期——单独用时间窗不够（5 分钟内可以重放任意多次）；
2. **Nonce** 彻底阻断重放——单独用 Nonce 不够（攻击者可以每次换新 nonce 重放，因为签名覆盖了 nonce，他改不了 nonce，但可以用同一份完整请求重放；Nonce 恰好解决这个）；
3. **常量时间比较**抵御时序攻击——`String.equals` 在首个不同字符处返回，攻击者能通过响应时间逐字节猜出签名。

**为什么 Nonce 的键要带 enterpriseId**：不同企业可能有相同的 nonce（概率极低但不应依赖）。带企业 ID 后，A 企业的 nonce 不会影响 B 企业。

**状态存储的作用**：Nonce 去重依赖 `StateStore`。**多实例部署时必须启用 Redis**，否则同一 nonce 可以在实例 A 与实例 B 各用一次——这直接削弱了防重放能力。

### 5.3 上传接口的业务态授权

已在 4.3.3 详述。此处补充**威胁模型**：

| 攻击者能力 | 攻击目标 | 本平台的防护 |
|---|---|---|
| 知道某个 `eventId` | 伪造该事故的视频证据 | 必须提供 `mediaRequestMsgId` 且与库中记录一致（该值只在下发的 MQTT 报文中出现） |
| 知道某个 `ackMsgId` | 用别车的编号上传 | `vin` 必须与事件所属车辆一致 |
| 未收到媒体请求 | 凭空灌入视频 | `mediaRequestMsgId` 为空 → `UNAUTHORIZED` |
| 重放合法上传 | 覆盖已归档证据 | 幂等：同 `eventId + 方向` 直接返回**已有**记录，不覆盖 |
| 上传超大文件 | 打爆磁盘/内存 | 200MB 上限（`PAYLOAD_TOO_LARGE`） |
| 路径穿越 | 写到任意目录 | `sanitize()` 净化目录段 + 扩展名白名单 |

**「重复上传不覆盖」是安全属性而非便利性**：如果后到的文件覆盖先到的，攻击者只要能重复上传就能替换证据内容。

### 5.4 敏感信息处理

| 类别 | 处理方式 | 位置 |
|---|---|---|
| MQTT 密码 | 动态换取 + 缓存，**不落配置文件默认值**；换取失败回落静态配置并告警 | `RegulatoryGateway` |
| 远驾驾驶员证件号 | 配置中以掩码存储（`5101**********1234`），前端二次打码 | `application.yml`、`RemoteDrivingView` |
| 媒体文件名 | 落盘用 UUID，不使用原始文件名（可能含车牌） | `MediaService.storeLocal` |
| 数据库口令 | **无默认值**，通过环境变量注入（`${DB_PASSWORD}`） | `application-prod.yml` |
| 日志 | 不打印完整凭据；`MqttMessageLog.payload` 超过 8000 字符截断 | `MqttAuditService.truncate` |
| 默认管理口令 | 启动时前端显示告警提示（TopBar 的「默认口令」提示） | `AppLayout` |

**为什么报文体要截断**：协议允许单条报文最大 256KB。若不截断，一条异常报文（如带 base64 图片）会让留痕表单行爆掉，且日志体积失控。截断到 8000 字符足以定位问题（`...[截断]` 标记）。

### 5.5 已知安全欠账

| 编号 | 欠账 | 风险 | 当前缓解 |
|---|---|---|---|
| S-01 | 管理端无 RBAC，单一角色 | 任何登录者拥有全部权限 | 内网部署；待出现多角色需求时升级 |
| S-02 | 令牌无法主动批量失效（只能单个 `revoke`） | 密钥轮换时需等待 12 小时自然过期 | `RegulatoryGateway.evictCredentials()` 处理的是上游凭据；管理令牌待补 |
| S-03 | 未接入 HTTPS（由部署层负责） | 明文传输令牌与凭据 | 文档 4.1 要求 TLS 1.2+，需在 Nginx 层强制；见 9.4 |
| S-04 | `X-Nonce` 去重依赖 Redis（单机模式下减弱） | 多实例部署若忘开 Redis，防重放降级为「实例内防重放」 | 启动日志会打印 `L2 分布式缓存不可用` 告警；见 6.3 |
| S-05 | 无请求体大小限制之外的频率保护（对外 API 无限流） | 监管平台侧异常重试可能放大 | 上游有自己的限流；本平台限流仅覆盖「单车辆」维度（见 6.4） |

---

## 6 数据与缓存设计

### 6.1 状态存储抽象（`StateStore`）

平台把三类「瞬时状态」放在键值存储里，而不是反复打数据库：

| 用途 | 键格式 | TTL | 不这样做的问题 |
|---|---|---|---|
| 报文去重 | `dedup:{senderId}:{msgId}` | 10 min | 每次判重查库，在 8640 万行表上不可行 |
| 车辆最新状态 | `vehicle:state:{vin}`、`vehicle:position:{vin}` | 30 min | 列表页每车一次「取最新一条」查询 |
| 心跳存在性 | `vehicle:exists:{vin}`、`vehicle:lastbase:{vin}` | 10 min | 1Hz 下每秒一次 `existsByVin` |
| 限流计数 | `rl:{scope}:{id}:{bucket}` | 窗口 × 2 | 见 6.4 |
| 令牌 | `auth:token:{token}` | 12 h | 无法多实例共享登录态 |
| Nonce | `auth:nonce:{eid}:{nonce}` | 10 min | 防重放失效 |
| 远驾舱分配 | `cockpit:last-used:{id}`、`cockpit:vin:{vin}` | 7 d | 接管记录丢失 |

**两种实现的切换**：`@ConditionalOnProperty(dssad.redis.enabled)`

| 实现 | 生效条件 | 适用 | 已知限制 |
|---|---|---|---|
| `InMemoryStateStore` | `dssad.redis.enabled=false`（默认，`matchIfMissing=true`） | 本地开发、单测、单机部署 | 多实例各持一份，去重与限流不准 |
| `RedisStateStore` | `dssad.redis.enabled=true` | 生产、多实例 | 需 Redis 可用 |

**内存版的两个设计取舍**：

1. **惰性过期 + 容量清理，不做后台扫描线程**。定时全表扫描在键量大时反而抢 CPU；改为「读时判定过期 + 达到容量上限时批量清理」。
2. **容量上限 200000**，超过则只记 warn 不拒绝写入——拒绝写入会导致后续判重全部失效（比多占一点内存更糟）。

**Redis 版的降级策略**（关键设计）：

```java
@Override
public boolean setIfAbsent(String key, String value, Duration ttl) {
    try {
        Boolean success = redisTemplate.opsForValue().setIfAbsent(key(key), value, ttl);
        return Boolean.TRUE.equals(success);
    } catch (RuntimeException e) {
        log.error("[状态存储] Redis SETNX 失败，已降级放行 key={}", key, e);
        return true;      // ← 降级「放行」
    }
}
```

| 操作 | Redis 异常时 | 理由 |
|---|---|---|
| `setIfAbsent`（去重） | 返回 `true`（放行） | 去重失败最多导致一次重复处理（业务侧有幂等），代价远小于「所有报文处理失败」 |
| `increment`（限流） | 返回 `0`（`RateLimiter` 识别为降级 → 放行） | 限流失败最多短暂超限 |
| `get` | 返回 `empty`（未命中） | 回落到数据库查询 |
| `put` | 静默忽略 | — |

**统一加 `dssad:` 前缀**：便于与同一 Redis 实例上的其他业务隔离与清理。

### 6.2 二级缓存

**为什么自研而不直接用 Spring Boot 自动配置**：`spring.cache.caffeine.spec` 是「一条字符串管全部」，而本平台不同缓存的时效要求差一个数量级（5s vs 60s）。统一 TTL 只能迁就最短的，等于白白丢掉命中率。

**结构**：

```
@Cacheable 调用
  │
  ▼
TwoLevelCache（AbstractValueAdaptingCache 子类）
  ├─ L1: Caffeine（进程内，maximumSize = 20000，按缓存名独立 TTL）
  │    └─ 命中 → 直接返回（不走网络）
  ├─ L2: Redis（跨实例，按缓存名独立 TTL，存信封 {"c":"类名","v":值}）
  │    └─ 命中 → 回填 L1 → 返回
  └─ 都未命中 → 调用目标方法 → 写 L1 + L2
```

**缓存清单与 TTL**：

| 缓存名 | 内容 | L1 TTL | L2 TTL | 键设计 |
|---|---|---|---|---|
| `dashboardOverview` | 大屏总览 | **5s** | 5s | `'all'`（单键） |
| `dashboardTrend` | 趋势折线 | 30s | 60s | 含 `days` 参数（**按天数分流**） |
| `dashboardFaultCategory` | 故障分类分布 | 30s | 60s | `'all'` |
| `dashboardFaultVehicles` | 故障车 Top 榜 | 30s | 60s | `'all'` |

**`dashboardTrend` 的 key 必须含 `days`**：否则第一次查 7 天，第二次查 30 天会命中同一个键，返回错误的天数范围。`DashboardCacheIntegrationTest` 专门钉死了这一点（「trend 按 days 分流 key」）。

**L2 信封 + 类名白名单**（这是自研缓存最需要小心的部分）：

```java
// L2 存信封 {"c":"类名","v":值}，反序列化时按类名白名单判断
// 白名单：java.util.*、com.genvict.dssad.cloud.* 及标量类型
```

**为什么需要白名单**：L2 的 JSON 反序列化如果允许任意类名，就等于给了一个「远程代码构造」的入口（虽然需要能写 Redis，但仍是不必要的攻击面）。白名单限制了可反序列化的类型范围。

**三处降级路径**（这是二级缓存的可靠性关键）：

| 情况 | 行为 |
|---|---|
| L2 为 `null`（未启用 Redis） | 退化为纯本地单级缓存，功能正常 |
| L2 抛异常 | 记 error，返回 L1 结果或继续走目标方法（**不让缓存故障变成业务故障**） |
| L2 payload 损坏/类名不在白名单 | 视为未命中，走目标方法（**不让脏缓存阻塞业务**） |
| `@Cacheable` 方法返回 `null` | **不缓存 null**（否则「查不到」会被缓存住，导致新数据写入后仍查不到） |

**`clear()` 只清 L1，L2 交给 TTL**：

```java
/**
 * clear() 只清 L1（L2 交 TTL）。
 */
```

**为什么不清 L2**：多实例部署下，`clear()` 只能清本实例的 L1；L2 若被清掉，其他实例的 L1 仍是旧值——**清了一部分反而让脏数据的分布变得不可预测**。统一交给 TTL 自然过期，行为更可推理。

**单测环境的处理（决策 A-15）**：

```yaml
# src/test/resources/application-test.yml
spring:
  cache:
    type: none          # NoOpCacheManager
dssad:
  cache:
    enabled: false      # CacheConfig 不生效
```

**两个开关都要关**。只关 `dssad.cache.enabled` 时，`spring.cache.type` 的默认值会让 Boot 自动配置一个 `ConcurrentMapCacheManager`——缓存依然存在，A 用例注入的报文会被 B 用例从缓存读到，**测试随机失败且极难定位**。为了让「@Cacheable 真的生效」这件事仍被验证，专门有 `DashboardCacheIntegrationTest` 显式装配真实缓存。

**装配自检日志**：

```
[缓存] 二级缓存已装配：L1=Caffeine(maxSize=10000)，L2=未启用（单机模式）；声明缓存=[...]
```

以及一条**必须存在的 error 告警**：

```java
if (redisEnabled && redis == null) {
    log.error("[缓存] dssad.redis.enabled=true 但未找到 StringRedisTemplate，"
            + "L2 分布式缓存不可用，已降级为仅本地一级缓存");
}
```

**没有这条日志，运维会「以为有二级缓存，其实只有一级」**——多实例下的表现是「各实例看到的数据不一致」，这条日志是唯一的线索。

### 6.3 限流器（滑动窗口计数）

**为什么不用令牌桶/漏桶**：

| 方案 | 每次判定开销 | 精度 | 本场景是否必要 |
|---|---|---|---|
| 令牌桶 | Lua 脚本或多次往返 | 高 | 不需要——限流键是「车辆」，需求只是「挡住异常车端打爆平台」 |
| 漏桶 | 同上 | 高 | 不需要 |
| **计数** | 2 次 GET + 1 次 INCR（约 1ms） | 足够 | ✅ 1Hz × 千车的高频路径上，网络往返才是瓶颈 |

**从固定窗口升级为滑动窗口**：固定窗口按 `now / W` 取整分桶，**边界处会突刺**：

```
固定窗口的问题（W = 60s，limit = 100）：
  第 59 秒：发送 100 次  → 全部通过（本桶计数 100）
  第 61 秒：发送 100 次  → 全部通过（新桶计数 100）
  实际效果：2 秒内通过 200 次 → 突破文档 100 次/分钟的口径

滑动窗口（双窗口加权）：
  bucket        = now / W
  elapsedRatio  = (now % W) / W
  estimated     = previousCount × (1 − elapsedRatio) + currentCount
  allow ⟺ estimated < limit
```

**直觉**：上一窗口的计数随本窗口的时间推进**线性衰减**。于是「刚跨窗时」上一窗的满额计数仍全额计入（抑制突刺），随着本窗口推进，上一窗影响逐渐归零。仅用 2 个键，不需要 Redis 有序集合。

**三个关键实现细节**：

**① 被拒绝的请求不计入计数**（先判定再自增）：

```java
if (estimated >= limit) {
    log.warn("[限流] 触发限制 scope={} id={} 当前窗口等效计数={} limit={}", ...);
    return false;                     // ← 直接返回，不 increment
}
long afterIncrement = stateStore.increment(currentKey, ...);
```

**为什么这条至关重要**：若拒绝也计数，在「上一窗满额」时持续的探测请求会让每个窗口都被填满，**导致限流永久无法恢复，车辆被彻底封死**。

**② 读→判定→自增之间的竞态被显式接受**：

```java
// 读计数 → 判定 → 自增之间存在竞态，极端并发下可能自增后才越界；
// 此时拒绝本次请求，但**不回滚计数**（回滚本身又是一次竞态）。
return afterIncrement <= limit;
```

代价是高并发下可能**略微超发**。对限流而言「少挡几个」远优于「误杀正常车」。回滚计数会引入新的竞态，因此不做。

**③ TTL 必须 > 1 个窗口**：

```java
/**
 * 限流键的存活时长 = 窗口 × 该倍数。
 *
 * <p>必须 > 1 个窗口，否则「上一窗口计数」在跨窗瞬间就已过期，
 * 滑动窗口会退化成固定窗口。
 */
private static final int TTL_WINDOW_MULTIPLIER = 2;
```

**全整数运算**：`previousWeighted = readCount(previousKey) * (windowMillis - elapsed) / windowMillis`——避免浮点误差导致边界判定抖动。

**当前覆盖范围与缺口**：

| 维度 | 是否限流 | 说明 |
|---|---|---|
| 单车辆 HTTP（100/分钟） | ✅ | `tryAcquireHttp(vin)` —— **已接线（v1.0.2）**：`EnterpriseRateLimitFilter` 挂在签名过滤器之后，VIN 取自请求体缓存，拒绝返回 `4001` + `Retry-After: 60` |
| 单车辆 MQTT 发布（10/秒） | ✅ | **已接线（v1.0.4）**：在业务发布唯一出口 `MqttCommandService.publish()` 执行 `tryAcquireMqttPublish(vin)`（VIN 取自 Topic 第 3 段，云云 Topic 无 VIN 不参与单车辆限流）。**被拒不丢弃**——报文转离线队列缓发，每秒 tick 的补传通道以受控速率（20 条/秒全局）倾倒，突发被自然摊平；审计状态记 `RATE_LIMITED`。ACK 回复**刻意不限流**：ACK 丢失会引发对端重投整条原报文，限流反而放大流量（协议自带收敛）。行为断言见 `MqttCommandServiceTest`（7 例） |
| 单车辆上行报文（入站） | ❌ | **未限流**——依赖入站队列容量保护（见 10 章 D-02），刻意设计 |
| 单企业对外 API 调用 | ❌ | 依赖上游自有约束与签名校验 |

---

## 7 可靠性设计

### 7.1 失败模式与降级策略总表

这是运维最该先看的一章。**每一种失败都有明确的、被设计过的行为**，而不是「看情况」。

| # | 失败点 | 检测方式 | 系统行为 | 数据影响 | 恢复方式 |
|---|---|---|---|---|---|
| 1 | MQTT Broker 不可达 | 连接异常 | 指数退避重连（0/2/4/8/16/32/60s） | 出站进离线队列 | 连接恢复后自动补传（20 条/秒） |
| 2 | MQTT 连接突然断开 | `connectionLost` | 状态 → `RECONNECTING` | Broker 保留 QoS 1 未确认消息（`clean_session=false`） | 自动重连 + 重订阅 + 补传 |
| 3 | 单条报文处理抛异常 | `catch (Throwable)` | 消费线程**继续运行**，`failedCount++` | 该条报文丢失（留痕 ERROR） | 对端按重发策略重投 |
| 4 | 入站队列满（20000） | `droppedCount++` | 丢弃**最旧**一条 | 丢最旧的报文 | 扩容消费线程或排查慢查询 |
| 5 | 离线队列满（10000） | `droppedByPressure` / `droppedLowPriority` | 按优先级取舍（见 3.5） | 低优先级被淘汰 | 调大 `offline-cache-size` |
| 6 | 重发超过上限 | `attempts > max` | 移除登记项 + **error 日志** | 该报文确认失败（需人工介入） | 排查对端处理能力或报文合法性 |
| 7 | 遥测缓冲超 100000 | `droppedCount++` | 丢弃采样点，每 10000 条打一次 error | 丢采样点（轨迹有空隙） | 排查数据库写入能力 |
| 8 | 数据库不可用 | JDBC 异常 | 业务方法抛异常 → 报文回 `res=2` | 该条报文未落库 | 对端重发 |
| 9 | Redis 不可用 | 操作异常 | **降级放行**（去重/限流）；缓存退化 L1 | 去重与限流精度下降 | Redis 恢复后自动恢复 |
| 10 | 监管平台 HTTP 失败 | `RestClientException` | **仅对可恢复错误重试**（超时/5xx，1s/2s/4s）；4xx 立即失败 | 上游调用失败 | 下个周期重试（如地图拉取） |
| 11 | 媒体落盘失败 | `IOException` | 抛 `STORAGE_ERROR`（5003） | 该文件未归档，状态不推进 | 车端重传 |
| 12 | 留痕写入失败 | `catch (RuntimeException)` | **记 error，不影响主业务** | 该条留痕丢失 | 无需恢复（留痕非关键路径） |
| 13 | 缓存的 L2 异常 | 反序列化/连接异常 | 视为未命中，走目标方法 | 无 | 自动恢复 |
| 14 | 分布式锁/Nonce 存储不可用 | Redis 异常 | 降级放行 | 防重放能力减弱 | Redis 恢复 |
| 15 | 时钟回拨 > 5ms | `nextId()` 检测 | **抛异常快速失败** | 拒绝生成 ID（宁可报错不写脏数据） | 修 NTP |
| 16 | 上游凭据换取失败 | `BizException` | 回落静态配置密码 + 告警 | 可能以旧凭据连接 | 修上游可用性 |
| 17 | 项目自建网关未配置远驾舱 | 配置为空 | 使用兜底资源 + **error 日志** | 监管侧可能拿到占位数据 | 补全配置 |

### 7.2 数据不丢的边界

「不丢数据」不是一个布尔值，必须说清**哪些会丢、为什么可接受、如何补救**：

| 数据类别 | 丢失场景 | 最坏损失量 | 为什么可接受 | 补救路径 |
|---|---|---|---|---|
| 事故/故障/任务/远驾 | 几乎不丢 | 单条报文（对端重发可补） | QoS 1 + 应用层 ACK 重发 + 幂等 | 对端重发；`pendingAccidents` 巡检 |
| 事故视频 | 上传失败 | 单个方向的文件 | 车端本地至少存 10 天（协议要求） | 车端重传（幂等，不覆盖） |
| 遥测采样点 | 进程崩溃 / 停机 | **最后 1 秒的采样点** | 1Hz 数据，丢 1 秒对轨迹形状无影响 | 车端不重发（QoS 0）；接受 |
| 遥测采样点 | 数据库持续不可用 | 缓冲满后持续丢 | 说明是容量问题，需运维介入 | 监控 `droppedCount` 告警 |
| 报文留痕 | 采样模式（`sampled` 5%） | 约 95% 报文不留痕 | **设计如此**：1000 台车全量留痕 = 8640 万条/天 ≈ 3 GB/天 | 需要全量时改 `mode: full` |
| 报文留痕 | 样本外的留痕表写入失败 | 单条留痕 | 留痕不是业务关键路径 | 无 |
| 离线队列内容 | 进程重启 | 全部剩余（进程内队列） | 只影响「重启前恰好未发出的下行指令」 | 人工重发（任务/远驾有业务路径） |
| 缓存内容 | 任何情况 | 全部（可从库重建） | 缓存本就是派生数据 | 自动重建 |

**关键结论**：本平台**唯一不可自愈的损失是「进程重启时离线队列中的下行指令」**。缓解：任务与远驾接管都有管理端的人工重发入口；且该队列通常为空（只在断线期间有内容）。

### 7.3 外部依赖容错

| 依赖 | 容错策略 | 实现要点 |
|---|---|---|
| **监管平台 HTTP** | 超时 30s（连接 + 读取都设）、重试 3 次（1s/2s/4s）、**仅对可恢复错误重试** | `RestClientException`（超时/连接失败/5xx）重试；`BizException`（4xx/错误码）立即失败——重试只会浪费 7 秒并放大对端压力 |
| **MQTT 凭据** | 按需拉取 + 缓存 + 提前 5 分钟刷新；失败回落静态配置 | **不在启动时阻塞**：拉取失败只告警，保证平台自身可用性不被外部依赖拖垮 |
| **SRS 流媒体** | 仅拼接地址，不做连通性检查 | 拉流失败由前端播放器报错；平台不做转码也不代理流 |
| **Redis** | 全部操作 try/catch 降级 | 见 6.1 的降级策略表 |
| **数据库** | 无降级（业务需要强一致） | 失败即失败，由对端重发 |

**`postForData` 的泛型陷阱规避**（值得记录）：

```java
/**
 * 反序列化分两步：先把整包解析为 JsonNode，取出 code/message 做统一校验，
 * 再把 data 节点转换为目标类型。
 * 这样做的好处是不同类型的业务接口共用同一套错误处理，
 * 且避免了 Java 泛型擦除导致的 Envelope<T> 反序列化陷阱
 * （直接用泛型包装类反序列化时，data 会退化成 LinkedHashMap）。
 */
```

这是 Java 泛型 + Jackson 的经典坑：`Envelope<T>` 在运行时 `T` 被擦除，`data` 会反序列化成 `LinkedHashMap`，然后在强转时抛 `ClassCastException`——**而且往往在很远的地方才抛**。分两步解析（先 `JsonNode`，再 `convertValue`）是干净解法。

### 7.4 可观测性

**三个层面**，分别对不同的使用者：

#### 7.4.1 指标端点（给运维/监控系统）

| 端点 | 免鉴权 | 内容 |
|---|---|---|
| `/api/v1/monitor/health-check` | ✅ | 各子系统状态（MQTT、缓存、限流算法） |
| `/api/v1/monitor/mqtt` | — | 连接状态、发布/失败计数、重连次数、队列积压与丢弃数、在册待确认数、离线条数（含两个丢弃计数） |
| `/api/v1/monitor/mqtt/stats?windowMinutes=N` | — | 时间窗内的报文总数、INVALID/UNSUPPORTED/ERROR 数、平均处理耗时 |
| `/api/v1/monitor/messages?senderId=&mqttType=&processStatus=&page=&size=` | — | 报文留痕分页（三个筛选可任意组合） |
| `/api/v1/monitor/messages/trace/{msgId}` | — | **按 msgId 全链路追溯**（上行 + ACK 下行成对呈现） |
| `/api/v1/monitor/cache` | — | 缓存形态（L1/L2 是否启用）、L1/L2 命中数、命中率、限流算法名 |
| `/actuator/health`、`/actuator/prometheus`、`/actuator/metrics` | ✅ | Spring Boot 标准端点 |

**`/monitor/messages/trace/{msgId}` 是排障的主力工具**：一条报文从上行到 ACK 的全部留痕，按时间排序即可看出「哪里卡住了」。它的前提是留痕表记录了 `msgId` 且建了索引（`idx_log_msg_id`）。

#### 7.4.2 日志规范

```yaml
logging:
  level:
    root: INFO
    com.genvict.dssad.cloud: INFO
    org.eclipse.paho: WARN     # Paho 断线重连时会打大量 DEBUG，压到 WARN
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n"
```

**日志中带 `[%thread]` 是刻意的**：排查时第一件事就是判断「这条日志来自哪个线程」。例如 `[mqtt-consumer-2]` 与 `[http-nio-8080-exec-5]` 的日志交错，才能看出问题出在消费侧还是请求侧。

**日志级别使用约定**：

| 级别 | 使用场景 | 示例 |
|---|---|---|
| `error` | 需要人工介入；数据可能不一致 | 重发超上限放弃、时钟回拨拒绝生成 ID、Redis 降级、留痕失败 |
| `warn` | 异常但可自愈；重要业务事件 | 事故上报、故障上报、远驾接管、限流触发、队列满淘汰 |
| `info` | 状态变更、生命周期 | 连接成功、订阅完成、任务开始/结束、归档完成 |
| `debug` | 排查细节（默认不输出） | 发送失败、重复确认、重连细节 |

**「事故/故障/远驾用 warn 而不是 info」是有意的**：这几类事件在生产环境日志里必须**一眼可见**。如果它们是 info，会被海量业务日志淹没。

#### 7.4.3 启动自检日志

平台在启动时会打印一组关键状态，**部署后第一时间应该核对这几行**：

```
[入站] 已启动 4 个报文消费线程（虚拟线程）
[路由] 已注册 10 个报文处理器，覆盖 18 种 mqttType
[缓存] 二级缓存已装配：L1=Caffeine(maxSize=10000)，L2=未启用（单机模式）；声明缓存=[...]
[MQTT] 未启用真实 Broker 连接（dssad.mqtt.enabled=false），平台将以「无 Broker 模式」运行...
[MQTT] 已连接并完成订阅，本次补传离线报文 N 条，剩余积压 M 条
```

| 日志行 | 需要确认的 |
|---|---|
| 消费线程数 | 是否与 `dssad.mqtt.consumer-threads` 一致 |
| 处理器数量与覆盖类型数 | 是否为 10 / 18（少于预期说明某个 Handler 未装配） |
| 缓存形态 | L2 是否符合预期（**多实例部署却显示「未启用」= 配置漏了**） |
| MQTT 模式 | 生产环境不应出现「无 Broker 模式」 |
| 订阅与补传 | 是否出现了订阅失败的 error（Broker ACL 未放通） |

**`[路由] 已注册 N 个报文处理器` 这行代码在启动期做了一件重要的事**：

```java
private void register(List<MessageHandler> handlers) {
    for (MessageHandler handler : handlers) {
        for (MqttType type : handler.supportedTypes()) {
            MessageHandler previous = handlerIndex.put(type, handler);
            if (previous != null) {
                throw new IllegalStateException("mqttType=" + type.getCode()
                        + " 被两个处理器注册：" + previous.getClass().getSimpleName()
                        + " 与 " + handler.getClass().getSimpleName());
            }
        }
    }
    ...
}
```

**同一类型被两个 Handler 注册时直接启动失败**（快速失败）。如果只是覆盖，会静默地只有一个生效——那种缺陷在联调时表现为「某种报文没反应」，极难定位。

---

## 8 前端设计

### 8.1 技术选型与工程结构

| 维度 | 选择 | 版本 | 理由 |
|---|---|---|---|
| 框架 | Vue 3（Composition API） | 3.5 | 与后端同为国内主流；`<script setup>` 类型推导好 |
| 构建 | Vite | 6 | 冷启动与 HMR 快；原生 ESM |
| 语言 | TypeScript | 5.7 | **类型是前后端契约的可执行文档**（本轮实践中直接抓出 4 个契约缺陷） |
| 组件库 | Element Plus | 2.9 | 表格/表单/分页开箱可用；深色主题变量完备 |
| 状态 | Pinia | — | 仅用于会话（auth），**不做全局业务状态**——业务数据一律由视图自身持有 |
| 图表 | ECharts 5 | — | 轨迹/趋势/分布图；`dark` 内置主题 |
| 时间 | dayjs | — | 轻量；与后端毫秒时间戳配合 |

**工程结构**：

```
cloud-platform-web/
├── index.html                  （class="dark" 启用 Element Plus 深色）
├── vite.config.ts              alias @→src；proxy 5 个前缀→8080；manualChunks 拆 vue/element/echarts
├── tsconfig.json               strict
├── .env.development            VITE_API_BASE_URL 空（走 proxy）；VITE_POLL_INTERVAL_MS=10000
├── .env.production             VITE_API_BASE_URL 空（同源 Nginx）
└── src/
    ├── main.ts                 注册 Pinia/router/ElementPlus(zh-cn)/全量图标/深色变量
    ├── App.vue                 零逻辑 <router-view/>
    ├── api/
    │   ├── http.ts             Axios 封装（拦截器、错误码、401 处理）
    │   ├── types.ts            全量 TS 类型（逐字段对照后端实体）
    │   └── index.ts            按域封装 10 个 API 组
    ├── stores/auth.ts          会话（localStorage 持久化）
    ├── router/index.ts         9 路由 + 鉴权守卫
    ├── layout/AppLayout.vue    左侧菜单 + 顶栏（MQTT 灯/时钟/默认口令告警/登出）
    ├── components/
    │   ├── EChart.vue          ECharts 封装（ResizeObserver、空态、aspect 等比例）
    │   └── StatCard.vue        统计卡（tone: default/ok/warn/danger）
    ├── utils/format.ts         时间/数值/字节格式化 + toCelsius
    ├── styles/index.css        深色变量（--dssad-*）+ 页面原子类
    └── views/                  10 个视图（见 8.5）
```

### 8.2 与后端的契约约定（最容易踩坑的部分）

以下 5 条是本项目**实际踩过坑**的约定，前端必须严格遵守：

| # | 约定 | 后端实现 | 踩坑记录 |
|---|---|---|---|
| C-1 | 响应体 `{code, message, data}`，**HTTP 200 不代表成功** | `GlobalExceptionHandler` 统一返回 HTTP 200 | 压测脚本首版把 HTTP 200 当成功，实际是业务码 2001（鉴权失败），导致误报 QPS 150 / P95 20ms |
| C-2 | 令牌走 **`X-Token`** 头，不是 `Authorization: Bearer` | `AdminTokenInterceptor.TOKEN_HEADER` | 用标准 Bearer 写法会一直 401 |
| C-3 | `Instant` → **13 位毫秒数字** | `write-dates-as-timestamps=true` | — |
| C-4 | `LocalDate` → **数组 `[年,月,日]`** | 同上（Jackson 对 `LocalDate` 也输出为数组） | 前端 `parseTime` 必须兼容数组形态 |
| C-5 | 车辆详情是**三段式** `{vehicle, latestState, position}` | `VehicleController.detail` | 前端若按顶层读，整个车辆档案显示 `—`、在线状态恒为「离线」——**静默失效** |

**C-5 是本轮 type-check 抓到的真实缺陷**，值得展开：

```typescript
// ❌ 错误：按顶层读
const detailFields = computed(() => (detail.value ?? {}) as Record<string, unknown>)
// 结果：detail.value.vin 为 undefined → 页面显示「—」

// ✅ 正确：嵌套读取
const detailFields = computed(() => {
  const d = (detail.value ?? {}) as VehicleDetail
  return d.vehicle ?? {}
})
```

这类缺陷**不报错、不抛异常、控制台干净**，只是页面上全是占位符。TS 类型是唯一能在编译期发现它的手段。

**另外两个由 TS 类型抓出的契约缺陷**：

| 缺陷 | 后果 | 修正 |
|---|---|---|
| `dispatchTask.action` 在前端是可选的 | 后端是 `@NotBlank`（1 开始 / 2 结束），漏传直接 `1001` | 改为必填 |
| 结束任务未强制复用 `taskId` | 配不上对就产生 `ORPHAN_FINISH` 孤儿记录 | 表单加校验 + 列表显著标红 |

### 8.3 HTTP 封装（`api/http.ts`）

**拦截器职责**（一处收口，视图层不用关心）：

```
请求拦截：
  └─ 有 token → 注入 X-Token 头

响应拦截：
  ├─ 解包 response.data（视图拿到的直接是业务层对象，不用写 .data.data）
  ├─ code != '0000' → 抛 ApiError(code, message)
  ├─ code == '2001' → 清会话 + 跳登录（通过 setUnauthorizedHandler 回调，避免循环依赖）
  └─ 网络异常 → 统一提示
```

**`setUnauthorizedHandler` 回调模式的必要性**：`http.ts` 需要跳转登录页（依赖 router），router 的守卫又依赖 auth store，auth store 依赖 `http.ts` — 直接互相 import 会形成**循环依赖**（在 ESM 下表现为运行时 undefined）。回调注册把依赖方向变成单向：

```typescript
// router/index.ts
setUnauthorizedHandler(() => router.replace({ name: 'login' }))
```

**类型化方法**：`get<T>` / `post<T>` / `upload<T>`——配合 `api/types.ts` 的全量类型，调用方 `await vehicleApi.detail(vin)` 即可得到 `VehicleDetail` 类型，字段名写错立刻报错。

### 8.4 状态管理与路由

| 项 | 设计 | 理由 |
|---|---|---|
| Pinia 用途 | **仅会话**（token、用户名、登录/登出） | 业务数据不需要全局状态——每个视图独立拉取，避免「缓存不一致」这类难查的问题 |
| 持久化 | localStorage（键 `dssad.token`） | 刷新页面不掉登录态；键名带项目前缀避免与其他应用冲突 |
| 路由 | 9 条：`login` + 8 个业务子路由，**全部懒加载** | 首屏只加载登录页；配合 `manualChunks` 让 vue/element/echarts 独立分包（生产构建产出的 3 个大 chunk） |
| 鉴权守卫 | `beforeEach`：无 token 且非 `login` → 重定向登录；已登录访问 `login` → 重定向首页 | 双向下重定向，避免「已登录还能打开登录页」 |
| 回跳 | 登录页读 `route.query.redirect` | 401 后跳登录，登录成功回到原页面 |

### 8.5 视图清单

| # | 视图 | 路由 | 核心内容 |
|---|---|---|---|
| 1 | `LoginView` | `/login` | X-Token 约定提示、默认口令告警、redirect 回跳 |
| 2 | `DashboardView` | `/dashboard` | 运营总览大屏：趋势折线 / 分类饼 / 故障车条形 / 故障码表 / 链路健康网格 |
| 3 | `VehicleListView` | `/vehicles` | 分页、关键词/类型/在线筛选；在线状态来自后端；最后上报相对时间 |
| 4 | `VehicleDetailView` | `/vehicles/:vin` | 实时状态卡、车辆档案、运行状态快照（含胎压数组与温度真值）、准静态参数变更表、**轨迹回放** |
| 5 | `EventCenterView` | `/events` | 待归档事故催办清单（按等待时长排序）+ 取证状态机（4 步 steps）+ 四方向视频归档表 + 故障明细 + 故障码 Top10 |
| 6 | `OperationView` | `/operations` | 任务下发/结束表单（坐标 `[纬度,经度]`）+ `ORPHAN_FINISH` 红色告警 + 导航路径等比例平面图 |
| 7 | `RemoteDrivingView` | `/remote-driving` | 接管控制（LRU 分配 + 发起人 1/2）+ 驾驶证号打码 + 记录异常列（缺驾驶员 / 已结束无时长） |
| 8 | `MapBarrierView` | `/map-barriers` | 阻断多边形等比例绘制（按阻断强度配色）+ 日期回看 + 立即同步（幂等说明） |
| 9 | `VideoView` | `/videos` | 取证上传联调台（ackMsgId 授权说明）+ 四方向取证进度格 + 模拟器一键造事故自动填表 |
| 10 | `MonitorView` | `/monitor` | 12 张链路指标卡 + 管道水位图 + 报文类型/结果分布 + 缓存命中率表 + 报文留痕检索 + msgId 全链路追溯 |

**轮询策略**：`DashboardView` 与 `MonitorView` 按 `VITE_POLL_INTERVAL_MS`（10s）轮询，在 `onBeforeUnmount` 清除定时器。**10 秒 > overview 的 5 秒缓存 TTL**，避免「刷新生效」的错觉与无效请求（见 4.10）。

**`EChart.vue` 封装的三个要点**：

| 要点 | 实现 | 为什么需要 |
|---|---|---|
| 容器尺寸变化 | `ResizeObserver` | 侧边栏折叠/窗口缩放时图表要重绘，`window.resize` 监听不到容器变化 |
| 空数据 | `isEmpty` 属性 → 显示空态 | 否则 ECharts 渲染一片空白，用户无法判断「没数据」还是「坏了」 |
| 等比例 | `aspect` 属性 → 容器 `aspect-ratio` | 轨迹回放要求横纵像素比例 1:1，否则轨迹形状失真（见 8.6） |

### 8.6 前端不接地图 SDK 的原因与替代方案

**决策 A-13**：轨迹与阻断多边形全部用**本地平面等比例投影**绘制，不接第三方地图。

**理由**：

1. **合规**：国内地图服务有 GCJ-02 坐标系偏移与底图审图要求，接入需要资质与额外处理；
2. **可用性**：底图依赖外部 CDN，一旦不可用整页失能（而轨迹判读是核心功能）；
3. **需求匹配**：本平台需要的判读是「轨迹形状」与「阻断多边形的位置关系」，不需要路网与 POI。

**等比例投影的实现**（`VehicleDetailView` / `MapBarrierView`）：

```typescript
// 经纬度 → 本地米平面
const METERS_PER_DEGREE_LAT = 110540;
const METERS_PER_DEGREE_LON = 111320 * Math.cos(latRadians);

// 数据空间补成正方形（否则等比缩放时会被拉伸）
// 容器 aspect-ratio: 1 / 1
```

**为什么必须补正方形 + 容器 1:1**：如果只做「按数据范围缩放」，横纵比例会各自独立拉伸，导致**轨迹形状失真**（一个直角转弯看起来像锐角）。补成正方形后单轴等比缩放，1 米横纵对应相同像素数。

**已声明的限制**：无路网/POI 背景；跨较大纬度范围时（> 数百公里）平面近似误差上升（本项目车辆运营范围为城市级，差异可忽略）。

---

## 9 部署视图

### 9.1 进程与端口

| 进程 | 端口 | 说明 |
|---|---|---|
| `dssad-cloud-platform.jar` | 8080 | Spring Boot 单体；Nginx 反代在其前 |
| MySQL | 3306 | 生产数据库 |
| Redis | 6379 | 可选；`dssad.redis.enabled=true` 时使用 |
| MQTT Broker | 1883（tcp）/ 8883（tls） | 监管平台提供 |
| SRS | 8080 / 1935 | 流媒体服务（实时视频拉流） |
| Vite dev server | 5173 | **仅开发态**；生产由 Nginx 托管静态产物 |

**单进程内包含三个逻辑服务**（HTTP + MQTT Client + 定时任务），刻意不拆微服务：本平台的规模（千车级）与团队规模不支持微服务的运维成本，拆分的收益（独立扩缩容）在此阶段不存在。

**启动命令**：

```bash
# 本地开发（默认 local profile：H2 文件库 + MQTT 关闭，零外部依赖）
mvn spring-boot:run

# 生产
java -jar dssad-cloud-platform.jar --spring.profiles.active=prod

# 注意：若环境中注入了 SERVER__PORT 变量（某些沙箱/容器会），需显式覆盖
java -jar dssad-cloud-platform.jar --server.port=8080
```

### 9.2 环境差异矩阵

| 配置项 | local | test | prod |
|---|---|---|---|
| 数据库 | H2 文件 `./data/db/dssad;MODE=MySQL` | H2 内存 | MySQL 8 |
| `ddl-auto` | `update` | `create-drop` | **`validate`** |
| MQTT | `enabled: false` | `false` | `true` |
| Redis | `false`（L2 自动降级） | `false` | `true` |
| 二级缓存 | 启用（L1 10000 / TTL 10s） | **完全关闭**（`spring.cache.type=none` + `dssad.cache.enabled=false`） | 启用（L1 50000） |
| 虚拟线程 | true | true | true（`spring.threads.virtual.enabled`） |
| 留痕模式 | `full`（调试需看每条） | `full`（断言需要） | `sampled` 5% |
| 留痕保留 | 7 天 | 1 天 | 180 天 |
| 轨迹保留 | 90 天 | 0（不清理） | 90 天 |
| 模拟器 | **`true`**（联调造数据） | `true` | **`false`（必须）** |
| 敏感配置 | 明文默认值 | 明文 | 全部 `${ENV_VAR}` 注入 |

**`ddl-auto=validate` 是生产的安全线**：它会在启动时校验实体与库表是否一致，不一致直接启动失败。好处是「结构漂移」立刻暴露；代价是**新增字段必须先执行 DDL 再发版**（顺序不能反）。这也是《数据库设计说明书》第 7 章那套「实体→生成 DDL→手写 DDL 三方核验流程」存在的原因。

**注意 `validate` 不校验索引**：实体上声明了 `@Index` 但库里没建，`validate` 不会报错——这类问题只会在查询变慢时暴露。这是《数据库设计说明书》发现 D-02~D-04（前导列不匹配的隐性全表扫描）时踩过的坑。

### 9.3 配置项全景

配置全部绑定到 `AppProperties`（12 个嵌套 record），分 12 组：

| 组 | 关键项 | 默认 | 调优建议 |
|---|---|---|---|
| `dssad.enterprise-id` | 企业 ID | `DSSAD-ENT-0001` | 由准入平台分配，**云云 Topic 的身份标识**，填错上下行全部对不上 |
| `dssad.mqtt` | `enabled` / `broker-url` / `keep-alive-seconds` / `clean-session` | false / tcp://127.0.0.1:1883 / 60 / false | `clean-session` 必须 false（持久会话） |
| `dssad.mqtt.consumer-threads` | 消费线程数 | 4 | **虚拟线程下可放心调大**：队列积压（`/monitor/mqtt` 可见）时先调这里，再看连接池 |
| `dssad.mqtt.virtual-threads` | 虚拟线程开关 | true | 排查 pinning 时关闭做 A/B |
| `dssad.mqtt.offline-cache-size` | 离线队列容量 | 10000 | 下限 100；断线时间长且下行多时调大 |
| `dssad.mqtt.retry.*` | 重发间隔与次数 | 5s×3 / 3s×5 | 与协议一致，**不建议改** |
| `dssad.mqtt.reconnect.*` | 退避初值/上限/倍数 | 2000 / 60000 / 2.0 | 网络抖动频繁时可缩短上限 |
| `dssad.regulatory.*` | 上游地址、路径、salt、超时、重试 | 见 yml | `secret-salt` 必须与上游一致 |
| `dssad.storage.*` | 存储类型/根目录/公开 URL/大小上限 | local / ./data/media / ... / 200MB | 上限与 `spring.servlet.multipart` 保持一致 |
| `dssad.api.max-page-size` | 分页上限 | 200 | 防止前端一次拉全表 |
| `dssad.api.track-simplify-tolerance` | 抽稀容差（米） | 8.0 | 调小更保真但返回点更多 |
| `dssad.api.history-video-max-span-hours` | 历史视频单次跨度上限 | 168 | 避免扫整月录像 |
| `dssad.rate-limit.*` | 开关 / HTTP 100·分钟 / MQTT 10·秒 | true | 与协议 9.1 一致 |
| `dssad.audit.*` | 模式 / 采样率 / 保留天数 | sampled / 0.05 / 180 | **省磁盘的首选旋钮**（见下） |
| `dssad.retention.*` | 轨迹保留 / 状态流水保留 / 批大小 | 90 / 180 / 5000 | 按磁盘反推；`0` = 不清理（仅接入初期） |
| `dssad.cache.*` | 开关 / L1 容量 / 各缓存 TTL | true / 20000 / 5~60s | 见 6.2 |
| `dssad.redis.enabled` | Redis 开关 | false | **多实例部署必须 true** |
| `dssad.simulator.enabled` | 报文注入模拟器 | false | **生产必须 false**（它能凭空造事故记录） |
| `dssad.remote-driving.cockpits` | 远驾舱资源池 | 2 个示例 | 未配置会使用兜底资源并打 error 日志 |

**磁盘不足时的调优优先级**（这是《数据库设计说明书》得出的结论）：

| 优先级 | 手段 | 效果 | 代价 |
|---|---|---|---|
| 1 | `audit.mode: full → sampled` | 留痕体积省 **95%** 以上 | 丢失大部分报文留痕（取证时依赖车端与上游） |
| 2 | `audit.mode: → event-only` | 只留事件类报文 | 只能查事故/故障相关报文 |
| 3 | 缩短 `retention.track-point-days` | 线性减少轨迹体积 | **不推荐优先做**——轨迹是事故回溯的原始证据 |
| 4 | 扩容磁盘 | — | 成本 |

**为什么优先降留痕而不是砍轨迹**：留痕的完整性由上游与车端共同承担（监管平台也有报文记录），而 1Hz 轨迹**只有本平台存**。砍轨迹等于永久失去事故回溯能力。

### 9.4 生产部署要点

**Nginx 反向代理（同源，前端零 CORS 配置）**：

```nginx
server {
    listen 443 ssl http2;              # 强制 HTTPS（文档 4.1 要求 TLS 1.2+）
    server_name dssad.example.com;

    # 前端静态产物
    location / {
        root /var/www/dssad-web;        # 即 cloud-platform-web/dist
        try_files $uri $uri/ /index.html;   # history 路由回退
    }

    # 5 个后端前缀统一转发
    location ~ ^/(api|enterprise|manager|media|actuator)/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;              # 限流与审计按真实 IP
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        client_max_body_size 210m;      # 与后端 multipart 上限一致（200MB + 余量）
        proxy_read_timeout 300s;        # 上传 200MB 文件需要
    }
}
```

**四个必须配置的点**：

| 点 | 不配的后果 |
|---|---|
| `X-Real-IP` / `X-Forwarded-For` | 后端 `forward-headers-strategy: framework` 拿不到真实客户端 IP，限流与审计全部按 Nginx 的 IP 统计 |
| `client_max_body_size` | 默认 1MB，事故视频上传（可达 200MB）全部 413 |
| `proxy_read_timeout` | 默认 60s，大文件上传中途断开 |
| `try_files ... /index.html` | 前端刷新任意子路由直接 404（history 模式必需） |

**虚拟线程下的连接池是首个瓶颈**：

引入虚拟线程后，Tomcat 能承载的并发请求大幅上升，但 `HikariCP` 默认连接池只有 **10**。此时表现是「请求在线程池里排队等连接」，`/actuator/metrics/hikaricp.connections.pending` 会持续 > 0。

| 并发量级 | 建议 `maximum-pool-size` | 说明 |
|---|---|---|
| ≤ 50 并发 | 10（默认） | 够用 |
| 50~200 并发 | 20~30 | 需同步确认数据库 `max_connections` |
| > 200 并发 | 先看慢查询，不要盲目加连接 | 连接多了会让数据库侧上下文切换成为瓶颈 |

**多实例部署的两个必须项**：

1. **`dssad.redis.enabled=true`**——否则去重、限流、Nonce、会话各自为政；
2. **`dssad.instance.worker-id` 显式配置且各实例不同**——雪花算法的机器位默认按本机 IP 末段推导，容器环境下多个实例可能得到相同 IP 段而**生成重复主键**。启动时若出现主键冲突，首先查这里。

**第三个必须项（当前未实现，见第 10 章）**：定时任务无分布式锁。多实例下 `scheduledPurge` / `scheduledPull` 会在每个实例各执行一次。

**备份与监控**：见《数据库设计说明书》第 8 章（含 `mysqldump` 策略、监控指标清单、扩容四步法）。

---

## 10 缺陷与待改进清单

以下是本轮设计评审中**确认存在但未修复**的问题（已修复的 D-01~D-09 见《数据库设计说明书》附录 B）。按严重程度排序。

| 编号 | 等级 | 问题 | 影响 | 建议方案 |
|---|---|---|---|---|
| **P-01** | **高** | ~~**多实例部署时定时任务会在每个实例重复执行**~~ ✅ **已修复（v1.0.3）**：新增 `DistributedTaskLock`（`common/schedule` 包，基于 `StateStore` 的 SETNX + token 比对释放），已接入 4 个定时任务——地图拉取（`map-barrier-pull`）、留痕清理（`mqtt-audit-purge`）、遥测清理（`telemetry-purge`）、在线回落扫描（`vehicle-offline-sweep`，随 P-02 新增）| 修复前：两个实例同时执行 `DELETE ... LIMIT` 会相互争抢行锁；地图拉取重复消耗监管平台配额 | ✅ 已落地：单机（内存 StateStore）恒可获锁、退化为无锁直通行为不变；Redis 多实例时互斥。刻意不引入 Redisson/ShedLock：释放非原子与无续期两个不严格处由「任务幂等 + TTL ≫ 最坏耗时」兜底（详见类 Javadoc）。⚠️ 两个秒级任务（ACK 重试、遥测刷盘）消费**实例本地内存缓冲**，**刻意不加全局锁**——加了会让抢不到锁的实例缓冲无人消费 |
| **P-02** | **高** | ~~**车辆上下线状态缺少定时刷新**~~ ✅ **已修复（v1.0.3）**：新增 `VehicleOnlineSweeper`（`fixedDelay = 30s` + 分布式锁），每 30 秒调用一次 `markOfflineVehicles()`，离线判定最迟滞后约 2.5 分钟（2 分钟阈值 + 30 秒扫描间隔）| 修复前：车辆断连后 `online` 字段永远保持 `true`，大屏「在线车辆数」持续偏高 | ✅ 已落地：批量 UPDATE 幂等；执行统计（轮数 / 累计置离线 / 最近执行时间）暴露在 `/api/v1/monitor/mqtt` 的 `vehicleOnlineSweep` 节点，掉线速率与链路健康同屏对照；行为断言见 `VehicleOnlineSweeperTest` |
| **P-03** | **高** | ~~**限流器完全未接入请求链路**~~ ✅ **已全部修复（HTTP 侧 v1.0.2 / MQTT 发布侧 v1.0.4）**：原问题——`RateLimiter` 的 `tryAcquireHttp` / `tryAcquireMqttPublish` 在全项目**零调用点**，配置的「单车辆 HTTP 100 次/分钟」「单车辆 MQTT 10 条/秒」一条都未生效（经《性能优化与压测报告》F-01 实测确认）| 修复前：一台异常企业集成可无上限占用业务线程与数据库写入能力，且监控端点仍报告 `enabled: true`（「显示已开启、实际没接线」的最危险组合）| ✅ HTTP 侧：`EnterpriseRateLimitFilter`（企业侧，拒绝响应 HTTP 200 + `4001` + `Retry-After: 60`），7 例行为断言，探针升级为发布门禁。✅ MQTT 发布侧（v1.0.4）：在 `MqttCommandService.publish()` 唯一出口接线，**被拒不丢弃**——转离线队列缓发（补传通道受控速率倾倒摊平突发），审计记 `RATE_LIMITED`；ACK 回复刻意不限流（丢失会引发对端重投放大流量）；7 例行为断言（`MqttCommandServiceTest`，含「未连接路径不消耗令牌」「云云 Topic 不限流」）。入站报文按 6.4 维持不限流（队列容量兜底，属设计决策）。限流触发计数已在 `/monitor/cache` 暴露（v1.0.3） |
| **P-04** | 中 | ~~**远驾接管配对依赖「最近一条未结束记录」**~~ ✅ **已部分修复（v1.0.5，方案②）**：协议未提供接管单号，在 `OperationService.onRemoteDriving` 加两道防御——① **发起侧防悬挂**：新发起时若存在未闭合旧接管（其结束报文已丢失），自动按「异常结束」闭合（时长取发起间隔、下限 0），每车同时至多一条未结束记录，配对不再有歧义；② **结束侧合理性校验**：结束时间早于发起时间（时钟偏差/乱序重投）时拒绝配对、按孤儿记录落库，不把负时长写进监管台账 | 修复前：结束报文丢失的旧接管会永久挂起（前端一直显示进行中）；结束早于发起的乱序报文会产生负时长污染台账 | 方案①（平台生成接管单号、车端回带）需上游协议支持，仍登记于《需求规格说明书》FR-15；行为语义由现有用例覆盖回归 |
| **P-05** | 中 | **离线队列为进程内队列，重启即丢** | 重启前恰好未发出的下行指令丢失（任务/远驾） | 序列化到 Redis List 或本地 WAL；或用 `clean_session=false` + QoS 1 让 Broker 承担（当前已部分覆盖）。**待评审**：方案对比见《技术选型与架构决策记录》T-01 |
| **P-06** | 中 | ~~**地图拉取的 `areaCode` 用 `System.getProperty` 读取**~~ ✅ **已修复（v1.0.5）**：改为 `AppProperties` 正式配置项 `dssad.regulatory.area-code`（环境变量 `DSSAD_REGULATORY_AREA_CODE`，已补入 prod yml 与 `.env.example`）；`pullToday` 对手动调用未传参的情况回落到配置值，避免把空串发给上游 | 修复前：生产无法经 yml/环境变量配置，默认空串静默拉到空结果且无告警 | ✅ 已落地：`scheduledPull` 直读配置；`application.yml` / `application-prod.yml` / `env.example` 三处同步 |
| **P-07** | 低 | **管理令牌无法批量失效** | 密钥轮换/人员离职时需等待 12 小时自然过期 | `TokenService` 增加按 subject 索引的令牌集合，支持 `revokeAll(subject)` |
| **P-08** | 低 | **无 RBAC**：单一角色 | 任何登录者拥有全部权限（含模拟器、远驾接管等高危操作） | 引入角色字段 + 拦截器按路径前缀校验；或高危操作增加二次确认 |
| **P-09** | 低 | **前端 10 个视图均无单元测试** | 重构时缺少回归保护；已通过 TS 类型 + 生产构建 + 端到端冒烟做了部分保护 | 引入 Vitest，优先覆盖 `parseTime` / `format*` 等纯函数与 `http.ts` 拦截器逻辑 |
| **P-10** | 低 | **`track-simplify-tolerance` 为全局单一值** | 不同场景（大屏概览 vs 事故复盘）需要不同精度 | 接口增加可选参数 `tolerance`，默认取配置值 |
| **P-11** | 低 | **无链路追踪（traceId）** | 跨 MQTT/HTTP/定时任务的调用链无法关联；日志格式已预留占位 | 接入 Micrometer Tracing + OTel，日志 pattern 已含占位可直接填 |
| **P-12** | 提示 | **`groute-resp` 作用域与协议不一致**（详见 3.1.1） | `high_speed` 车型可能收不到 ACK | 待上游澄清；已做兼容处理，澄清后改一处枚举即可 |

**关于 P-01 与 P-02 的说明**：这两条从「代码看起来是完整的」角度最难发现——P-01 需要意识到 Spring 的 `@Scheduled` 是进程级的；P-02 需要发现「方法存在但从无调用方」。这类问题无法通过阅读单个类发现，只能靠**全局检索注解与调用关系**（本次评审即通过 `grep -rn "@Scheduled"` 与检查调用方发现）。✅ 两条均已于 v1.0.3 修复（`DistributedTaskLock` + `VehicleOnlineSweeper`）；修复时对每个新组件都补了「谁调用它」的行为断言测试，防同一族问题复发。

---

## 附录 A 关键类索引

### A.1 按对外接口定位入口类

| 接口（节选） | 控制器 | 服务 |
|---|---|---|
| `POST /enterprise/api/v1/video/live` | `EnterpriseApiController` | `MediaService.liveVideo` |
| `POST /enterprise/api/v1/video/history` | `EnterpriseApiController` | `MediaService.historyVideo` |
| `POST /enterprise/api/v1/remote-cockpit/start` | `EnterpriseApiController` | `RemoteCockpitService.startTakeover` |
| `POST /enterprise/api/v1/accident/media` | `AccidentMediaUploadController` | `AccidentMediaService.ingest` |
| `POST /api/v1/auth/login` | `AuthController` | `TokenService.issue` |
| `GET /api/v1/dashboard/*` | `DashboardController` | `DashboardService` |
| `GET /api/v1/vehicles` / `/{vin}` / `/{vin}/track` | `VehicleController` | `VehicleService` / `TrackQueryService` |
| `GET /api/v1/events/*` | `EventController` | `EventService` |
| `GET/POST /api/v1/operations/*` | `OperationController` | `OperationService` / `RemoteCockpitService` |
| `GET/POST /api/v1/map-barriers/*` | `MapBarrierController` | `MapBarrierService` |
| `GET /api/v1/monitor/*` | `MqttMonitorController` | `MqttAuditService` / `TelemetryService` / `TwoLevelCacheManager` |
| `GET /api/v1/dict/*` | `DictController` | `FaultCodeDictionary` / `MediaService` |
| `POST /api/v1/simulator/*` | `SimulatorController` | `MqttMessageRouter`（直接注入报文） |

### A.2 按 MQTT 报文类型定位处理器

| mqttType | Handler | 主要协作者 |
|---|---|---|
| `inh` | `InhMessageHandler` | `VehicleService.applyStaticParam` |
| `low-base` | `LowBaseMessageHandler` | `TelemetryService.onLowBase` |
| `low-state` | `LowStateMessageHandler` | `TelemetryService.onLowState` |
| `accident` | `AccidentMessageHandler` | `EventService` + `MqttCommandService.requestAccidentMedia` |
| `error-report` | `FaultReportMessageHandler` | `EventService.onFaultReport` |
| `accident-media-resp` | `AccidentMediaRespHandler` | `EventService.onAccidentMediaResp` |
| `groute` | `GrouteMessageHandler` | `OperationService.onGroute` |
| `mapehn`（下行） | `MapehnDownstreamHandler` | `MapBarrierService.onPush` + `MqttCommandService.replyMapehn` |
| 6 种 `*-resp`（下行） | `EnterpriseAckResponseHandler` | `PendingAckRegistry`（由 Router 完成配对） |

### A.3 基础设施类

| 职责 | 类 | 关键方法 |
|---|---|---|
| MQTT 连接生命周期 | `PahoMqttClientManager` | `connect` / `scheduleReconnect` / `onConnected` / `flushOffline` |
| 入站隔离 | `InboundMessageQueue` | `offer`（满则丢最旧）/ `take` / `droppedCount` |
| 入站消费 | `MqttInboundWorker` | `consumeLoop`（虚拟线程）/ `stop` |
| 报文路由 | `MqttMessageRouter` | `route`（8 步流水线） |
| 出站收口 | `MqttCommandService` | `publish` / `replyAck` / `requestAccidentMedia` / `reportTask` |
| Topic 互逆 | `TopicBuilder` / `TopicParser` | `ackTopic` / `subscriptionTopics` / `parse` |
| 应用层 ACK | `PendingAckRegistry` | `register` / `complete` / `markInvalid` / `due` / `advance` |
| 重发调度 | `MqttAckRetryScheduler` | `tick`（1s） |
| 离线队列 | `OfflineMessageQueue` | `offer`（优先级淘汰）/ `drain` |
| 留痕与去重 | `MqttAuditService` | `recordInbound` / `isDuplicate` / `purgeExpired` |
| 状态存储 | `StateStore` / `InMemoryStateStore` / `RedisStateStore` | `put` / `get` / `setIfAbsent` / `increment` |
| 二级缓存 | `TwoLevelCache` / `TwoLevelCacheManager` | `lookup` / `clear`（只清 L1）/ `stats` |
| 限流 | `RateLimiter` | `tryAcquireHttp` / `tryAcquireMqttPublish` |
| ID 生成 | `SnowflakeIdGenerator` | `nextId`（时钟回拨保护） |
| 轨迹抽稀 | `TrackSimplifier` | `simplify`（DP，单位统一到米） |
| 上游对接 | `RegulatoryGateway` | `fetchMqttPassword` / `accessToken` / `fetchTodayMapehn` / `postForData`（重试） |
| 签名 | `RegulatorySigner` | `secret` / `hmacSha256` / `verify`（常量时间） |
| 数据保留 | `TelemetryRetentionService` | `purgeExpired`（分批 native DELETE） |

---

## 附录 B 与《接口文档》《数据库设计说明书》的交叉引用

| 本文小节 | 关联文档位置 |
|---|---|
| 3.1 Topic 模型与 18 类报文 | 接口文档 3.2 / 3.3 / 附录 B.2 |
| 3.1.1 groute 作用域缺陷 | 接口文档 附录 C 缺陷清单 |
| 3.3 报文处理流水线 | 接口文档 3.4（处理流水线） |
| 3.4 应用层 ACK 重发 | 接口文档 3.5（重发规则） |
| 3.5 离线上传优先级 | 接口文档 3.6（离线补传） |
| 4.3 事故取证闭环 | 接口文档 5.2 / 5.3；数据库设计 3.3（`t_accident_event` / `t_media_asset`） |
| 4.5 轨迹抽稀 | 接口文档 6.x（车辆接口）；数据库设计 3.2（`t_vehicle_track_point`） |
| 6.1 状态存储 | 数据库设计 1.1（Redis 职责） |
| 6.2 二级缓存 | 数据库设计 1.1 / 8.x |
| 9.3 磁盘调优优先级 | 数据库设计 5.3（容量估算）/ 附录 A |
| 10 缺陷清单 | 数据库设计 附录 B（已修复项 D-01~D-09） |

---

## 附录 C 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0.0 | 2026-09-23 | 首次发布。覆盖系统架构、线程模型、MQTT 通道、10 个业务模块、安全、缓存、可靠性、前端、部署，并给出 12 项待改进清单（P-01~P-12）。 |
| v1.0.1 | 2026-09-23 | 依据《性能优化与压测报告》F-01 的实测结论修订 P-03：原描述「入站报文无频率限流」不准确——实测（同 VIN 连打 130 次全部成功）+ 静态核查（`tryAcquireHttp`/`tryAcquireMqttPublish` 零调用点）表明**三条限流均未接入请求链路**；等级由「中」升为「高」，修复方案移入性能报告 5.5。 |
| v1.0.2 | 2026-09-23 | **P-03 的 HTTP 侧修复落地**：6.4 维度表更新（HTTP 行标注已接线 `EnterpriseRateLimitFilter`；「MQTT 发布 ✅」修正为「⚠️ 能力已备、未接线」——`tryAcquireMqttPublish` 实际零调用，此前标 ✅ 与事实不符）；P-03 状态同步，遗留 MQTT 发布维度并说明难点。测试规模 200 → 207。 |
| v1.0.3 | 2026-09-23 | **P-01 / P-02 修复落地**：新增 `DistributedTaskLock`（`common/schedule`，SETNX + token 比对释放；单机退化无锁直通、Redis 多实例互斥，刻意不引入 Redisson 的取舍见类 Javadoc），接入 4 个定时任务；新增 `VehicleOnlineSweeper`（30s + 分布式锁）接线 `markOfflineVehicles`，在线回落闭环；限流触发计数（`httpRejectedTotal` / `mqttPublishRejectedTotal` / `lastRejectedAt`，毫秒口径）与回落统计（`vehicleOnlineSweep`）暴露到监控端点。测试规模 207 → 220。 |
| v1.0.4 | 2026-09-23 | **P-03 收尾：MQTT 下行发布限流接线**：在业务发布唯一出口 `MqttCommandService.publish()` 接入 `tryAcquireMqttPublish(vin)`（VIN 取自 Topic 第 3 段，云云 Topic 无 VIN 不参与单车辆限流）；**失败模式采用「转离线队列缓发」而非丢弃/抛异常**——不破坏 ACK 收敛（触发源一半是 MQTT 入站事故报告的问题就此消解），补传通道每秒受控倾倒自然摊平突发；审计状态新增 `RATE_LIMITED`。6.4 维度表「单车辆 MQTT 发布」由 ⚠️ 改 ✅。ACK 回复与入站维持不限流（原因见 6.4）。新增 `MqttCommandServiceTest` 7 例，测试规模 220 → 227。**至此三条限流维度全部接线完毕**。 |

---

**文档结束**


