# 车路通 DSSAD 产品云平台

车路通 DSSAD（低速自动驾驶数据）产品云平台 —— **企业云端 + 车辆数据接入 + 对外监管接口**。

平台接收车端经 MQTT 上报的运行状态、故障、事故、轨迹等数据，提供管理前端与运营监控；
同时按《平台数据对接接口文档 v1.0.0》向准入监管平台开放云云 HTTP 接口，
并在事故发生后联动下发取证请求、接收四方向视频归档。

---

## 技术栈

| 层次 | 选型 |
|------|------|
| 后端 | Spring Boot 3.5.16 · Java 21（虚拟线程）· JPA/Hibernate 6.6 · Caffeine + Redis 二级缓存 · Eclipse Paho MQTT 3.1.1 |
| 数据库 | MySQL 8.0.17+（生产）· H2（本地/测试） |
| 前端 | Vue 3 · Vite 6 · TypeScript 5.7 · Element Plus 2.9 · Pinia · ECharts 5 |
| 测试 | JUnit 5 · Mockito · MockMvc · JaCoCo（行覆盖门槛 0.60，绑定 `verify`） |
| 可观测 | Actuator（来源 IP 白名单收敛）· Micrometer Prometheus registry · 自研 MQTT 通道探针 |
| 部署 | Docker 多阶段镜像 / jar + systemd / Nginx |

---

## 快速开始（克隆即跑）

```bash
# 1. 环境自检（先跑这个：环境问题会伪装成代码问题）
bash scripts/check-env.sh

# 2. 启动（后端 local profile 零外部依赖：H2 文件库 + MQTT 关闭 + 报文模拟器开启）
bash scripts/dev.sh                 # 后端 8080 + 前端 5173
bash scripts/dev.sh --api           # 只起后端

# 3. 全链路冒烟（14 条断言：写入→读出→鉴权→登出失效）
bash scripts/health.sh
```

访问 <http://127.0.0.1:5173>，登录账号 `admin` / `dssad@2026`
（开发默认口令，生产必须用环境变量 `DSSAD_ADMIN_USER` / `DSSAD_ADMIN_PASSWORD` 覆盖）。

停止：`bash scripts/dev.sh --stop`

### 用打好的包在本机起服（上线前终验）

```bash
bash scripts/build.sh --web                        # 后端 jar + 前端 dist（含测试与覆盖率）
bash scripts/run-local.sh --bg                     # 成品 jar + local profile
bash scripts/run-local.sh --prod                   # 成品 jar + prod profile（需 .env 与依赖就绪）
bash scripts/run-local.sh --stop                   # 优雅停止（SIGTERM → 等待 → SIGKILL）
```

### 用容器起本地依赖

```bash
docker compose up -d                               # MySQL 8.4 + Redis 7（首次自动执行建表脚本）
docker compose --profile mqtt up -d                # 额外起 Mosquitto（联调 MQTT 时）
docker compose down                                # 停止（保留数据卷）
docker compose down -v                             # 停止并清空数据
```

### 备份与恢复

```bash
bash scripts/backup.sh --out /backup/dssad --keep 30          # 一致性备份 + 三重校验 + 轮转
bash scripts/backup.sh --out /backup/dssad --restore-check    # 额外做真恢复演练（建临时库→比对表数→删除）
bash scripts/backup.sh --dry-run                              # 预演，不写任何文件
```

---

## 目录结构

```
CloudPlatform/
├── cloud-platform-server/              后端（Spring Boot，独立 Maven 工程）
│   ├── src/main/java/com/genvict/dssad/cloud/
│   │   ├── config/                     配置装配（AppProperties / CacheConfig / WebConfig）
│   │   ├── mqtt/                       MQTT 通道：接入队列、消费线程、路由、可靠性、通道探针
│   │   ├── service/                    业务服务
│   │   ├── web/                        REST 控制器
│   │   ├── security/                   令牌鉴权 · 云云 HMAC 签名 · Actuator IP 白名单 · CIDR 匹配
│   │   └── common/                     响应体、错误码、二级缓存、状态存储、工具
│   ├── db/schema-mysql.sql             生产建表脚本（13 表 / 47 索引）
│   ├── perf/                           压测与限流探针脚本、实测数据存档
│   └── src/test/                       227 个用例（22 个测试类）
├── cloud-platform-web/                 管理前端（Vue 3 + Vite）
├── scripts/                            开发与运维脚本（见下）
├── deploy/                             nginx / systemd / mosquitto 部署资产
├── docs/                               设计文档（见下）
├── docker-compose.yml                  本地依赖编排
├── docker-compose.prod.yml             生产编排
└── Dockerfile                          后端镜像（多阶段）
```

### 脚本速查

| 脚本 | 用途 |
|------|------|
| `scripts/check-env.sh` | 环境自检（JDK/Maven/Node/端口/必填变量），输出「缺什么、怎么补」 |
| `scripts/dev.sh` | 本地开发启动（后端 + 前端），`--stop` / `--logs` |
| `scripts/build.sh` | 打包（走 `mvn verify`，覆盖率不达标即失败），`--web` / `--docker` / `--clean` |
| `scripts/run-local.sh` | 用成品 jar 起服，`--bg` / `--stop` / `--prod` / `--port` |
| `scripts/health.sh` | 全链路冒烟断言，`--no-simulator` 用于生产态实例 |
| `scripts/mqtt-watch.sh` | **MQTT 通道存活探针**（退出码即故障分类，可挂 cron/Zabbix），`--deep` 附带积压/丢弃检查 |
| `scripts/backup.sh` | **MySQL 一致性备份 + 三重校验 + 轮转**，`--restore-check` 真恢复演练，退出码分级便于告警 |
| `scripts/check-env.sh --gen-secret 24` | 生成强随机口令（填 `.env` 用） |

> 完整命令手册（含底层真命令、逐项注释、排障速查、git 双远程推送）：[服务启停与状态检测命令集](docs/devops/服务启停与状态检测命令集.md) · [深色 HTML 版](docs/devops/服务启停与状态检测命令集.html)

---

## 文档索引

| 文档 | 内容 |
|------|------|
| [接口文档](docs/api/DSSAD云平台接口文档.md) | REST + MQTT 全量接口，与上游 PDF 逐条对齐 |
| [数据库设计说明书](docs/db/DSSAD云平台数据库设计.md) | 13 张表 / 索引与查询对照 / 容量估算 / 数据生命周期 |
| [详细设计说明书](docs/design/DSSAD云平台详细设计说明书.md) | 架构决策、线程模型、报文流水线、状态机、失败模式降级 |
| [性能优化与压测报告](docs/perf/DSSAD云平台性能优化与压测报告.md) | 实测数据集、缓存 A/B 对照、限流探针 |
| [开发与部署说明](docs/devops/DSSAD云平台开发与部署说明.md) | 三条路径、部署拓扑、配置参考、排障 FAQ |
| [测试说明](docs/test/DSSAD云平台测试说明.md) | 17 类 / 207 用例逐类详述、覆盖率与门槛、未覆盖清单 |
| [运维手册与告警预案](docs/ops/DSSAD云平台运维手册与告警预案.md) | 巡检清单、三层探活模型、指标与阈值、9 个应急剧本、发布回滚、备份扩容、O 系列缺陷清单 |
| [服务启停与状态检测命令集](docs/devops/服务启停与状态检测命令集.md) | 启停 / 状态检测 / 依赖 / 备份 / 生产部署 / git 双远程推送的全部命令，含底层真命令与注释（[HTML 版](docs/devops/服务启停与状态检测命令集.html)） |
| [市场调研与产品定位](docs/product/市场调研与产品定位.md) | 政策背景、目标客户画像、竞争格局与差异化、商业模式建议（[HTML 版](docs/product/市场调研与产品定位.html)） |
| [需求规格说明书（PRD）](docs/product/需求规格说明书.md) | 角色与用例、FR/NFR 需求编号与验收口径、验收基线、范围外（[HTML 版](docs/product/需求规格说明书.html)） |
| [技术选型与架构决策记录（ADR)](docs/design/技术选型与架构决策记录.md) | 9 条核心选型的背景/决策/理由/代价，待评审决策清单（[HTML 版](docs/design/技术选型与架构决策记录.html)） |
| [用户操作手册](docs/ops/用户操作手册.md) | 面向运营人员：各页面操作步骤、常见问题、升级路径（[HTML 版](docs/ops/用户操作手册.html)） |

每份文档同时提供**深色主题单文件 HTML**（零外部依赖，可直接双击打开或内网分发）。

---

## 安全须知（投产前逐条确认）

- [ ] `DSSAD_ADMIN_USER` / `DSSAD_ADMIN_PASSWORD` 已覆盖开发默认口令（不覆盖等于没有鉴权）
- [ ] 数据库/Redis 口令通过环境变量注入，仓库内无明文（`.gitignore` 已排除 `.env` 与密钥文件）
- [ ] 已启用 HTTPS（TLS 1.2+），`X-Token` 不允许明文传输
- [ ] `/actuator/**` **双层收敛**已就位：
      ① Nginx 层只放行 `health` / `liveness` / `readiness`，其余 404；
      ② 应用层 **来源 IP 白名单**（`ActuatorIpWhitelistFilter`，fail-closed），
      配置项 `dssad.security.actuator.allowed-cidrs` / `DSSAD_ACTUATOR_ALLOWED_CIDRS`，白名单外一律 404。
      ⚠️ 容器或 K8s 部署时 Nginx/kubelet 的源地址**不是回环**，必须把 Docker 网段或节点网段加入白名单，
      否则网关侧探活会拿到 404（`docker-compose.prod.yml` 已按此默认配置）
- [ ] 已配置**通道告警**：`scripts/mqtt-watch.sh` 挂上 cron 或监控平台，退出码 20/40 触发 P0
      （**探活全绿不代表能收到数据**，见运维手册 O-01）
- [ ] 已配置**每日备份 + 每月恢复演练**：`scripts/backup.sh` 注册进 cron（命令见运维手册 7.2）。
      **没恢复过的备份不算备份** —— 恢复演练是巡检项，不是可选项
- [ ] MySQL 与 Redis 不对公网暴露（生产编排中二者仅接 `internal` 网络）
- [ ] `dssad.simulator.enabled=false`（模拟器会凭空写入事故/故障，污染监管取证链路）
- [ ] 数据保留期已按磁盘容量核算（`DSSAD_TRACK_RETENTION_DAYS` 等），非 0

---

## 已知待改进项

设计与实测共记录 30+ 项缺陷/风险，按编号分布在对应文档中：

| 编号 | 归属 | 代表项 |
|------|------|--------|
| `P-01` ~ `P-12` | 详细设计 | 多实例 `@Scheduled` 无分布式锁；车辆 `online` 永不回落 |
| `D-01` ~ `D-09` | 数据库设计 | 遥测流水缺保留策略（已修复）；索引与查询不匹配（已修复） |
| `F-01` | 性能报告 | **限流器未接入请求链路**（P0，**已修复**）—— 原：配置 100 次/分钟，实测 130 次全部放行；现已接入企业侧链路并补行为断言 |
| `E-01` ~ | 部署说明 | 编排探活不覆盖 MQTT 通道，全绿但收不到数据（**已修复**，见 `O-01`） |
| `O-01` ~ `O-10` | 运维手册 | 通道探活盲区（已修复）；Prometheus 端点声明但缺依赖（已修复）；优雅停机未生效（已修复）；**`/actuator/**` 无鉴权（已修复）**；无自动备份（已修复）；多实例定时任务无锁（未修复） |

**尚未修复的高优先级项**：无 —— `P-01`/`O-07`（分布式锁）、`P-02`（在线回落）、`P-03`（限流接线）、`P-04`（远驾配对防御，方案②）、`P-06`（区域编码配置化）均已修复；余下为 `P-05`（离线队列持久化，待评审）/`P-07`~`P-12` 低级项。
均为**实测/代码复核**结论，不含推测项；已修复项的验证命令与实测输出见对应文档。

---

## 一个贯穿全项目的教训

本项目已累积 5 例同类缺陷 —— `F-01`（限流器配了但没接线，**已修复并补行为断言**）、`E-03`（覆盖率门槛声明了但没有 check 执行）、
`P-02`（方法无调用方）、`E-01`（状态字段无人消费）、`O-03`（`AdminTokenInterceptor` 里的 `/actuator`
放行分支因路径模式不匹配而永不执行）。

共同特征：**「写了、有注释、有配置，但没有任何调用方或消费者」**。
因此本项目的验证约定是：对这类东西一律要有一条**行为断言**兜底，而不是断言「类/属性存在」；
反过来，凡是「声明了却没人用」的配置项，都当作缺陷处理。
