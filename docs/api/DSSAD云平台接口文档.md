# DSSAD 云平台 接口文档

| 项 | 内容 |
|---|---|
| 文档名称 | DSSAD 云平台接口文档（车云 / 云云 / 企业云端） |
| 版本 | V1.0.0 |
| 密级 | 内部 |
| 编制日期 | 2026-09-22 |
| 上游依据 | 招商智行（重庆）科技有限公司《数据接口对接文档》V1.0.0（`平台数据对接接口文档v1.0.0-5.pdf`，共 68 页） |
| 适用系统 | `cloud-platform-server`（Spring Boot 3.5.16 / Java 21）+ `cloud-platform-web`（Vue 3 + Vite 6） |

---

## 0. 文档说明

### 0.1 本文档回答什么

本文档是 DSSAD 云平台**全部对外与对内接口的唯一权威描述**，覆盖三条数据通道：

1. **车云 MQTT**（第 3 章）：车端 ↔ 监管平台云端，本平台以「企业侧订阅方 / 下行发起方」身份参与；
2. **云云 MQTT**（第 3 章）：企业云端 ↔ 监管平台云端，本平台是**上行发起方**；
3. **企业云端 HTTP**（第 4 章）：监管平台 → 本平台**主动调用**，本平台必须实现；
4. **平台管理 API**（第 6 章）：供本平台 Web 控制台使用，非对外接口。

### 0.2 与上游接口文档的关系

本文档**不重新定义**上游已定义的字段语义。两者关系如下：

| 层 | 上游《数据接口对接文档》V1.0.0 | 本文档 |
|---|---|---|
| 字段名与类型 | 定义方 | 逐字复刻，不重命名 |
| 错误码 | 仅定义 `0000 成功` | **扩展**为 9 个区间的完整字典（第 2.3 节） |
| 认证方式 | 车云 `X-Token`；云云 `secret = SHA256(clientId + "CMAT")` | **企业云端 HTTP 接口的鉴权由本平台自定义**（上游 6.3.1 已授权「各企业根据自己已有平台的接口认证方式实现」），方案见第 4.1 节 |
| 协议缺陷 | — | 全部登记在第 7.3 节「文档缺陷与澄清清单」，并给出本平台的兼容策略 |

> **阅读顺序建议**：对接方先读第 2 章（通用约定，含错误码与鉴权），再按自身角色读第 3 章（MQTT）或第 4 章（HTTP）。

### 0.3 术语表

| 缩略语 | 说明 |
|---|---|
| ABS / ACC / AEB / ESP / TCS | 制动防抱死 / 自适应巡航 / 自动紧急制动 / 车身电子稳定 / 牵引力控制 |
| CAN / DMS / EDR | 控制器局域网络 / 驾驶员监测系统 / 事件数据记录系统 |
| DSSAD | 自动驾驶数据记录系统（本项目名，车端黑匣子） |
| GCJ-02 | 国家测绘地理信息局坐标系统（火星坐标，本项目经纬度基准） |
| GNSS / RTK / IMU | 全球导航卫星系统 / 实时动态差分 / 惯性测量单元 |
| LCA / LDW / LKA | 变道辅助 / 车道偏离预警 / 车道保持辅助 |
| QoS | MQTT 服务质量等级（0 至多一次 / 1 至少一次 / 2 恰好一次） |
| SRS | Simple Realtime Server，本项目使用的流媒体服务 |

### 0.4 版本历史

| 版本 | 日期 | 变更摘要 |
|---|---|---|
| V1.0.0 | 2026-09-22 | 首版。冻结 MQTT 20 类报文、企业云端 5 个 HTTP 接口、平台管理 47 个 REST 接口 |

---

## 1. 总体设计

### 1.1 系统边界与数据通道

```
                      ┌──────────────────────────────────────┐
                      │      监管平台云端（招商智行）           │
                      │  MQTT Broker（mqtts://…:1883）        │
                      │  HTTP  /passport /manager 接口        │
                      └───────┬──────────────────┬───────────┘
                              │                  │
              通道 B：云云 MQTT │                  │ 通道 C：HTTP（平台→企业云端）
              （双向，企业主动）  │                  │ （平台主动调用本平台）
                              │                  │
    ┌─────────────────────────▼──────────────────▼──────────────────┐
    │              DSSAD 云平台（本系统 = 企业云端）                    │
    │                                                                │
    │  MqttBridge ──► InboundMessageQueue ──► MqttInboundWorker(虚拟线程)│
    │                                              │                 │
    │                                       MqttMessageRouter        │
    │                                              │                 │
    │                     ┌────────────────────────┴──────┐          │
    │                     ▼                               ▼          │
    │              10 个 MessageHandler            留痕/幂等/ACK       │
    │                     │                                          │
    │         ┌───────────┼────────────┬──────────────┐              │
    │         ▼           ▼            ▼              ▼              │
    │    TelemetryService AccidentService MediaService MapBarrierService
    │         │           │            │              │              │
    │         └───────────┴────────────┴──────────────┘              │
    │                          │                                     │
    │              H2 / MySQL + Caffeine(L1) + Redis(L2)             │
    └──────────────────────────┬─────────────────────────────────────┘
                               │
              通道 A：车云 MQTT  │ （本平台订阅下行 + 车端上报）
                               │
                      ┌────────▼─────────┐
                      │  车端（无人车/   │
                      │  智能网联车）     │
                      │  经 HTTP 上传视频 │
                      └──────────────────┘
```

### 1.2 通道职责矩阵

| 通道 | 协议 | 本平台角色 | 本平台动作 | 章节 |
|---|---|---|---|---|
| A 车云 | MQTT | 订阅方 + 下行发起方 | 订阅 `uvodp/+/+/+/down`（可选 `+/up`）；发布 `accident-media/down` 与各类 `*-resp/down` | 3.6 |
| B 云云 | MQTT | 上行发起方 + 订阅方 | 发布 `task`/`remote-driving`/`groute`/`mapehn-resp`；订阅 `uvodp/{enterpriseId}/+/down` | 3.5.2 |
| C 企业云端 | HTTP | 服务提供方 | 实现 5 个 POST 接口（视频 2 个 + 远驾 2 个 + 媒体上传 1 个） | 4 / 5 |
| — 管理 | HTTP | 服务提供方 | 47 个 REST 接口供 Web 控制台 | 6 |

### 1.3 接口数量总览

| 分类 | 数量 | 鉴权方式 | 明细章节 |
|---|---:|---|---|
| MQTT 报文类型 | 18（上行 10 / 下行 8） | MQTT 用户名密码 + Topic 身份 | 3.3 |
| 企业云端 HTTP（对外） | 4 | HMAC-SHA256 签名（第 4.1 节） | 4.2 – 4.5 |
| 车端媒体上传 | 1 | `ackMsgId` 业务态鉴权（第 5 章） | 5 |
| 平台管理 API（认证 / 总览 / 车辆 / 事件 / 运营 / 阻断 / 字典 / 监控 / 模拟器） | 47 | `X-Token` 请求头 | 6 |
| HTTP 状态码语义 | — | **恒返回 200，业务码在响应体 `code`** | 2.2 |
| 业务错误码 | 12 | — | 2.3 |

---

## 2. 通用约定

### 2.1 传输与编码

| 项 | 约定 |
|---|---|
| 字符串编码 | UTF-8 |
| 请求/响应格式 | JSON（`Content-Type: application/json`）；媒体上传为 `multipart/form-data` |
| TLS | 最低 TLS 1.2，推荐 TLS 1.3（上游 4.1） |
| 时间戳 | 东八区 UTC 时间，**13 位毫秒长整型**（如 `1736840005000`） |
| 经纬度 | GCJ-02 坐标系 |
| 视频编码 | H.264；实时流 `m3u8` / `flv`，历史文件 `mp4` |
| 字符集边界 | 所有响应体为 UTF-8，且 `Content-Type` 显式带 `charset=UTF-8` |

### 2.2 统一响应体

**所有** HTTP 接口（含对外接口、管理接口、错误响应）一律返回如下结构：

```json
{
  "code": "0000",
  "message": "成功",
  "data": { }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | String | 业务码，4 位字符串，`0000` 为成功，其他见 2.3 |
| `message` | String | 可读提示，直接展示给用户或记入日志 |
| `data` | Object / Array / null | 业务数据；无数据时为 `null`（**字段始终存在，不省略**） |

> ⚠️ **最重要的一条约定**：本平台**不会**用 HTTP 状态码表达业务结果。
> 鉴权失败、限流、参数错误一律返回 **HTTP 200 + 非 `0000` 的 `code`**。
> 客户端**必须**判断响应体中的 `code`，仅判断 HTTP 状态码会把「未授权」当成「成功但数据为空」。
> 该约定已在前端 `http.ts` 拦截器中固化，并有端到端冒烟用例覆盖。

**成功响应示例**

```json
{
  "code": "0000",
  "message": "成功",
  "data": {
    "totalVehicles": 128,
    "onlineVehicles": 96
  }
}
```

**失败响应示例**

```json
{
  "code": "2001",
  "message": "未授权或Token已失效，请重新登录",
  "data": null
}
```

### 2.3 错误码字典

上游文档 7.1 节仅定义了 `0000 成功`。本平台在其基础上扩展为 9 个区间，**扩展码不与上游冲突**（上游预留的 4 位数字码中仅使用了 `0000`）：

| 区间 | 含义 | 错误码 | 含义 | 触发场景 |
|---|---|---|---|---|
| `0xxx` | 成功 | `0000` | 成功 | — |
| `1xxx` | 请求 / 参数 / 签名 | `1001` | 请求参数不合法 | 字段缺失、类型错误、越界；`@Valid` 校验失败 |
| | | `1002` | 签名校验失败 | 云云接口 HMAC 不匹配、时间偏差超 5 分钟、Nonce 重放 |
| | | `1003` | 请求体超出限制 | 超过 10MB 请求体上限 |
| `2xxx` | 认证鉴权 | `2001` | 未授权或 Token 已失效 | `X-Token` 缺失 / 过期（12 小时）/ 已登出 |
| `3xxx` | 资源 | `3001` | 资源不存在 | VIN 未注册、事件 ID 不存在 |
| | | `3002` | 资源已存在 | 幂等冲突 |
| `4xxx` | 限流配额 | `4001` | 请求过于频繁，已触发限流 | 单车辆 HTTP 超 100 次/分钟 |
| `5xxx` | 外部依赖 | `5001` | MQTT 通道不可用 | Broker 未连接 / 发布失败 |
| | | `5002` | 监管平台调用失败 | 上游 HTTP 超时（30s）或重试 3 次后仍失败 |
| | | `5003` | 媒体存储异常 | 磁盘写入失败 / MinIO 不可用 |
| `9xxx` | 系统内部 | `9999` | 系统内部错误 | 未预期异常，**详情只记日志不外泄** |

> 字典通过 `GET /api/v1/dict/error-codes` 运行时下发，前端据此把 `code` 映射为可读提示，避免前后端各维护一份。

### 2.4 认证与鉴权

三条链路使用**三套独立**的鉴权机制，这是刻意的设计——各链路的信任模型和载荷特征差异很大：

#### 2.4.1 平台管理 API：`X-Token`（第 6 章）

| 项 | 约定 |
|---|---|
| 请求头 | `X-Token: <token>`（**不是** `Authorization: Bearer`） |
| 获取方式 | `POST /api/v1/auth/login` 返回 `data.token` |
| 有效期 | 12 小时（滑动不过期，到期需重新登录） |
| 白名单（无需令牌） | `/api/v1/auth/login`、`/api/v1/monitor/health-check`、`/api/v1/simulator/**`、`/actuator/**`、`OPTIONS` 预检 |
| 令牌格式 | UUID v4（122 位随机），不可猜测 |
| 存储 | 默认内存（单机），启用 Redis 后多实例共享登录态 |
| 失效响应 | HTTP 200 + `{"code":"2001"}`；前端拦截器收到 `2001` 会清会话并跳登录页 |

#### 2.4.2 企业云端 HTTP（对外）：HMAC-SHA256 签名（第 4 章）

上游 6.3.1 明确「各企业根据自己已有平台的接口认证方式实现」。本平台采用**带防重放**的签名方案：

| 请求头 | 必填 | 说明 |
|---|---|---|
| `X-Enterprise-Id` | 是 | 企业 ID（准入平台分配） |
| `X-Timestamp` | 是 | 13 位毫秒时间戳，与服务端偏差 **≤ 5 分钟** |
| `X-Nonce` | 是 | 一次性随机串（UUID），**10 分钟内不可重复** |
| `X-Sign` | 是 | 见下方算法 |

**签名算法**（`secret` 派生方式与上游 5.2.2.1.1 一致）：

```
secret   = SHA256(enterpriseId + "CMAT")
待签串    = timestamp + "." + nonce + "." + <原始请求体字符串>
X-Sign   = HMAC-SHA256(secret, 待签串) 的小写十六进制
```

**服务端校验顺序**（任一失败即返回对应错误码）：

1. 四个请求头齐全 → 否则 `2001`；
2. `X-Timestamp` 可解析且偏差 ≤ 5 分钟 → 否则 `1002`；
3. `X-Nonce` 首次出现（`setIfAbsent` 原子写入）→ 重复则 `1002`「疑似重放攻击」；
4. 签名常量时间比对（`MessageDigest.isEqual`，抵御时序攻击）→ 不匹配则 `1002`。

**Java 参考实现**

```java
String timestamp = String.valueOf(System.currentTimeMillis());
String nonce     = UUID.randomUUID().toString();
String secret    = sha256Hex(enterpriseId + "CMAT");
String body      = objectMapper.writeValueAsString(payload);

Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
String sign = HexFormat.of().formatHex(
        mac.doFinal((timestamp + "." + nonce + "." + body).getBytes(StandardCharsets.UTF_8)));

// 设置 4 个请求头后 POST，body 必须是同一份字节序列
// 注意：签名基于【原始字节】，任何 JSON 空格差异都会导致校验失败
```

> **签名坑点提示**：`本平台按收到的原始请求体字节计算签名`。若客户端先签名再经网关重排 JSON 键顺序或压缩空白，会直接 `1002`。建议在签名与发送之间**不经过任何中间件改写**。

#### 2.4.3 车端媒体上传：`ackMsgId` 业务态鉴权（第 5 章）

该接口**不做签名校验**，原因有两条（上游文档要求的 `X-Token` 是监管平台侧机制，本平台作为企业云端不持有）：

1. **技术原因**：`multipart/form-data` 的 body 是可达 200MB 的二进制流，读入内存做 HMAC 会带来严重的内存与延迟问题；
2. **业务原因**：本平台已有更强的业务态约束——**车端只能对平台已经下发过媒体请求的事件上传视频**。校验逻辑为：

```
车端上传 (vin, eventId, cameraDirection, ackMsgId)
  → 查 (vin, eventId) 对应的事故记录
  → 记录必须存在                    （否则 3001）
  → ackMsgId 必须等于该事故的 mediaRequestMsgId，即平台下发 accident-media/down 时的 msgId
                                    （否则 1001）
  → 该 (eventId, cameraDirection) 尚未归档  （否则 3002，避免重复覆盖证据）
```

这已足以阻止「任意视频污染证据链」，且不牺牲大文件吞吐。该取舍登记在 7.3 节 D-07。

#### 2.4.4 MQTT 层鉴权（第 3 章）

| 项 | 车云 | 云云 |
|---|---|---|
| `username` | VIN（如 `LSVT9123456789012`） | 企业 ID |
| `clientId` | `vin_{vin}` | `e_{enterpriseId}` |
| `password` | 上游 5.2.2.1.1 换取（`SHA256(clientId + "CMAT")` 派生） | 上游 6.2.2.1.1 换取 |

本平台作为企业云端使用云云身份：`clientId = e_{enterpriseId}`，`username = enterpriseId`（默认 `DSSAD-ENT-0001`），`password` 留空时自动通过上游 `/passport/api/v1/auth/access-enterprise-mqtt` 换取。

### 2.5 时间、坐标与数值约定

#### 2.5.1 JSON 序列化（前后端契约，务必对齐）

后端 Jackson 配置 `write-dates-as-timestamps: true`，导致两类时间呈现形式不同：

| Java 类型 | JSON 形式 | 示例 | 说明 |
|---|---|---|---|
| `Instant` | 13 位毫秒数字 | `1736840005000` | 与协议时间戳一致，**直接可用** |
| `LocalDate` | **数组** `[年, 月, 日]` | `[2026, 9, 22]` | 不是字符串！前端需转换后才能显示 |
| `LocalDateTime` | 数组 `[年,月,日,时,分,秒]` | `[2026,9,22,17,44,30]` | 同上 |

> `LocalDate` 转成数组是 Jackson 的默认行为，容易在联调时被误判为「接口返回了奇怪的数组」。
> 平台前端已在 `src/utils/format.ts` 的 `parseTime()` 中统一兼容毫秒、秒、ISO 字符串、数组四种形态。
> **接口文档的其他约定以本节为准，各接口条目不再重复标注。**

#### 2.5.2 坐标约定（易错点）

协议中坐标一律为 `Double[2]`，**第一位是纬度，第二位是经度**，即 `[latitude, longitude]`：

```
currentPoint = [30.67, 104.06]   ✅ 纬度在前
currentPoint = [104.06, 30.67]   ❌ 经度在前（成都/重庆一带会正好落在陆地上，不易被察觉）
```

影响接口：`accident/up`、`error-report/up`、`task/up` 的 `taskStartPoint`/`taskEndPoint`、`groute/up` 的 `pathPoints`/`currentPoint`、`mapehn/down` 的 `barrierPolygon`。

异常处理：**单点阻断**时 `barrierPolygon` 只有 1 个坐标；**区域阻断**时首尾坐标为同一值（闭环）。

#### 2.5.3 数值偏移量

| 字段 | 报文原文 | 实际值 |
|---|---|---|
| `temperature`（电池温度） | 上报值 | `上报值 - 100` = 摄氏度。如上报 `70` → 实际 `-30℃` |
| `accSpeed`（加速度） | 上报值 | 单位 **0.01 m/s²**，即上报 `2.5` → `0.025 m/s²` |
| `heading`（航向角） | 上报值 | 正北为 0 度顺时针，精度 0.01 度 |
| `tirePressure` | 数组，单位 kPa | 顺序：从车头开始、从左到右（前左1, 前左2, 前右1, 前右2, …） |

> **偏移量是本项目最容易「静默出错」的地方**：漏减 100 会让大屏显示「电池温度 135℃」，
> 数值离谱但不会报错。因此本平台的 `GET /api/v1/vehicles/{vin}/state` 响应
> **同时返回** `temperature`（协议原文）与 `temperatureCelsius`（真值），
> 由调用方按用途选择，从接口层面消除误用可能。参见 6.3.3 节。

### 2.6 分页约定

**请求参数**（标注为分页的接口均使用这两个参数）：

| 参数 | 类型 | 默认 | 上限 | 说明 |
|---|---|---|---|---|
| `page` | int | `1` | — | 页码，从 **1** 开始（不是 0） |
| `size` | int | `20` | **200**（`dssad.api.max-page-size`） | 每页条数，超上限按上限截断 |

**响应结构**（`PageResult<T>`）：

```json
{
  "code": "0000",
  "message": "成功",
  "data": {
    "list":       [ /* 当前页数据 */ ],
    "total":      137,
    "page":       1,
    "size":       20,
    "totalPages": 7
  }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `list` | Array | 当前页数据数组，无数据时为 `[]`（不是 `null`） |
| `total` | long | 满足条件的总条数 |
| `page` / `size` | int | 回显请求参数 |
| `totalPages` | int | `ceil(total / size)`，前端直接用于分页控件 |

> **`size` 设上限的原因**：轨迹点表按 1Hz 增长（1000 台车 ≈ 8640 万条/天），
> 不设上限的话一次 `size=100000` 足以打爆内存。需要全量数据请走专用的轨迹抽稀接口（6.3.6）。

### 2.7 限流与体积限制

#### 2.7.1 限流（对应上游 9.1）

| 接口类型 | 上游约定 | 本平台实现 | 算法 |
|---|---|---|---|
| MQTT 消息发布 | 10 条/秒·车 | `dssad.rate-limit.mqtt-per-second = 10` | **滑动窗口计数器** |
| HTTP API 调用 | 100 次/分钟·车 | `dssad.rate-limit.http-per-minute = 100` | **滑动窗口计数器** |

**为什么不用固定窗口**：固定窗口在窗口切换点允许「2 倍突发」（前一窗口末尾 100 次 + 新窗口开头 100 次），对车载 1Hz 上报场景会造成周期性冲击。滑动窗口用上窗计数按剩余时间加权：

```
estimated = previousCount × (windowMillis - elapsed) / windowMillis + currentCount
若 estimated ≥ limit → 拒绝
```

两个关键行为：

- **被拒请求不计数**：避免客户端持续重试导致计数累积、形成永久封禁；
- **拒绝响应**：HTTP 200 + `{"code":"4001"}`，客户端应退避后重试，不要立即重发。

#### 2.7.2 体积与并发限制

| 项 | 限制 | 配置项 |
|---|---|---|
| MQTT 单条消息 | ≤ 256 KB | — |
| HTTP 请求体 | ≤ 10 MB | Spring `max-request-size` |
| 文件上传 | ≤ 200 MB | `dssad.storage.max-upload-bytes = 209715200` |
| 单车辆 MQTT 连接数 | 1 | Broker 侧 `clientId` 唯一性保证 |
| 轨迹抽稀容差 | 8.0 米 | `dssad.api.track-simplify-tolerance` |
| 历史视频单次查询跨度 | ≤ 168 小时 | `dssad.api.history-video-max-span-hours` |

---

## 3. MQTT 接口规范

### 3.1 连接参数

| 参数 | 车云通道 A | 云云通道 B |
|---|---|---|
| Broker | `mqtts://{监管平台MQTT地址}:1883` | `mqtts://{监管平台MQTT地址}:1883` |
| 协议版本 | MQTT 3.1.1 / 5.0 | 同左 |
| `keep_alive` | 60 秒 | 60 秒 |
| `clean_session` | **`false`**（持久会话，断线期间 QoS 1 消息不丢） | 同左 |
| `qos_level` | 0 / 1 / 2（按报文类型，见 3.3） | 同左 |
| `clientId` | 车端：`vin_{vin}` | 本平台：`e_{enterpriseId}` |
| `username` | 车端：VIN | 本平台：企业 ID |
| `password` | 上游 5.2.2.1.1 换取 | 上游 6.2.2.1.1 换取；本平台留空时自动换取 |

> **`clean_session=false` 的意义**：这是上游文档明确要求的。设为 `true` 时 Broker 会在每次连接时清除会话，
> 断线期间对端发出的 QoS 1 消息将永久丢失——对事故上报这类关键数据不可接受。

### 3.2 Topic 命名规则

#### 3.2.1 规则定义（上游附录 B.1）

```
上行（对端 → 监管平台，平台订阅）：
  车云格式：uvodp/{vehicleType}/{vin}/{mqttType}/up
  云云格式：uvodp/{enterpriseId}/{mqttType}/up

下行（监管平台 → 对端，对端订阅）：
  车云格式：uvodp/{vehicleType}/{vin}/{mqttType}/{version}/down
  云云格式：uvodp/{enterpriseId}/{mqttType}/down
```

| 段 | 含义 | 取值 |
|---|---|---|
| `uvodp` | 固定前缀 | 常量 |
| `vehicleType` | 无人车类型 | `low_speed`（功能性无人车）/ `high_speed`（智能网联车） |
| `vin` | 车辆唯一标识 | 车辆识别代码 |
| `enterpriseId` | 企业唯一标识 | 准入平台分配，本平台默认 `DSSAD-ENT-0001` |
| `mqttType` | 消息类型 | 见 3.3 |
| `version` | 协议版本段 | **仅车云下行出现，可选**；为空时该段整体省略 |

> **`{version}` 段的省略规则**：附录 B.1 的下行格式含 6 段（带 `version`），但正文 5.1.5.x 的 Topic
> 只写了 5 段（不带）。本平台构造下行 Topic 时，`version` 为空则自动省略该段，
> 从而**同时兼容两种写法**；解析端两种均能识别。

#### 3.2.2 本平台的订阅过滤器

本平台**只用 3 个过滤器**覆盖全部场景，而不是为每种类型单独订阅（否则订阅数会随类型数线性膨胀）：

| # | 过滤器 | 覆盖内容 | 条件 |
|---|---|---|---|
| 1 | `uvodp/{enterpriseId}/+/down` | 云云下行：`mapehn/down`、`task-resp/down`、`remote-driving-resp/down` | 始终订阅 |
| 2 | `uvodp/+/+/+/down` | 车云下行：`accident-media/down` 与各类 `*-resp/down` | 始终订阅 |
| 3 | `uvodp/+/+/+/up` | 车云上行：车端上报的全部业务报文 | `dssad.mqtt.subscribe-car-uplink = true`（默认开） |

> **过滤器 3 的用途**：本平台在「车端直连监管平台」形态下不需要它；
> 但在**企业自建网关代车端上报**或车端直连企业侧的部署形态下，平台需要直接消费车端上行数据入库，
> 此时必须订阅。默认开启以兼容两种形态，可用配置关闭以避免重复消费。

#### 3.2.3 Topic 构造纪律（实现约束）

所有向外发送的 Topic **必须**经 `TopicBuilder` 构造，禁止在业务代码中字符串拼接。
原因：Topic 拼错不会抛异常——对端订阅不到、Broker 也不报错，消息**静默丢失**，
这类问题在联调阶段极难定位（表现为「对端一直收不到数据」，双方各查半天）。

### 3.3 mqttType 字典（共 18 类）

> 该表是**协议的单一事实来源**：Topic 构成、QoS、是否需要回复、回复主题、离线补传优先级全部由它推导。

| # | mqttType | 数据类别 | 归属 | 方向段 | QoS | 需回复 | 回复类型 | 补传优先级 | 上游章节 |
|---:|---|---|---|---|:---:|:---:|---|---|---|
| 1 | `inh` | 车辆准静态参数信息 | 车云 | `/up` | 1 | ✅ | `inh-resp` | 基础 | 5.1.4.2.1 |
| 2 | `inh-resp` | 车辆准静态参数回复 | 车云 | `/down` | 1 | — | — | — | 5.1.5.1.1 |
| 3 | `low-state` | 车辆低频运行状态信息 | 车云 | `/up` | **0** | ❌ | — | 状态 | 5.1.4.3.1 |
| 4 | `low-base` | 车辆低频基础信息 | 车云 | `/up` | **0** | ❌ | — | 状态 | 5.1.4.3.2 |
| 5 | `accident` | 车辆事故信息 | 车云 | `/up` | 1 | ✅ | `accident-resp` | **事件** | 5.1.4.4.1 |
| 6 | `accident-resp` | 车辆事故信息回复 | 车云 | `/down` | 1 | — | — | — | 5.1.5.1.2 |
| 7 | `error-report` | 车辆故障信息 | 车云 | `/up` | 1 | ✅ | `error-report-resp` | **事件** | 5.1.4.4.2 |
| 8 | `error-report-resp` | 车辆故障信息回复 | 车云 | `/down` | 1 | — | — | — | 5.1.5.1.3 |
| 9 | `accident-media` | 车端事故媒体数据请求 | 车云 | `/down` | 1 | — | — | — | 5.1.5.2.1 |
| 10 | `accident-media-resp` | 车端事故媒体数据请求回复 | 车云 | `/up` | 1 | ❌ | — | **事件** | 5.1.4.1.1 |
| 11 | `remote-driving` | 远程驾驶接管信息 | 云云 | `/up` | 1 | ✅ | `remote-driving-resp` | **事件** | 6.1.4.2.1 |
| 12 | `remote-driving-resp` | 远程驾驶接管信息回复 | 云云 | `/down` | 1 | — | — | — | 6.1.5.1.1 |
| 13 | `task` | 任务信息 | 云云 | `/up` | 1 | ✅ | `task-resp` | **事件** | 6.1.4.2.2 |
| 14 | `task-resp` | 任务信息回复 | 云云 | `/down` | 1 | — | — | — | 6.1.5.1.2 |
| 15 | `groute` | 车端导航路径信息 | 云云 ⚠️ | `/up` | 1 | ✅ | `groute-resp` | 基础 | 6.1.4.2.3 |
| 16 | `groute-resp` | 车端导航路径信息回复 | **车云** ⚠️ | `/down` | 1 | — | — | — | 6.1.5.1.3 |
| 17 | `mapehn` | 地图增强信息 | 云云 | `/down` | 1 | ✅ | `mapehn-resp` | — | 6.1.5.2.1 |
| 18 | `mapehn-resp` | 地图增强信息回复 | 云云 | `/up` | 1 | — | — | 基础 | 6.1.4.1.1 |

**统计**：上行 10 类（`/up`）、下行 8 类（`/down`）；需回复的 8 类（其中 `mapehn` 是**唯一一个下行也要回复**的类型）。

> ⚠️ **两处方向异常，对接时必须注意**：
> 1. **`groute` / `groute-resp` 作用域不对称**：请求 `uvodp/{enterpriseId}/groute/up` 是云云 3 段，
>    而回复 `uvodp/{vehicleType}/{vin}/groute-resp/down` 是车云 5 段。请求 Topic 里没有车辆身份，
>    单靠镜像无法构造回复主题。本平台的兼容策略与影响范围见 7.3 节 D-01。
> 2. **`mapehn` 的回复方向是 `/up`**：多数 `*-resp` 走 `/down`，但 `mapehn-resp` 按上游 6.1.4.1.1 是**上行**。
>    若实现时把回复方向写死为 `/down`，回复会发到无人订阅的主题，上游持续重发 `mapehn`，
>    表现为车端被**重复下发地图增强数据**。本平台在 `TopicBuilder.ackTopic()` 中已按回复类型自身方向构造，未写死。

### 3.4 ACK 与重发机制

#### 3.4.1 消息接收标志 `res`

所有回复报文的 `res` 字段取值（上游各 5.1.5.1.x / 6.1.5.1.x 章节）：

| `res` | 含义 | 本平台产生该值的条件 | 对端应做的动作 |
|---|---|---|---|
| `1` | 确认 | 校验通过且业务处理成功；**重复报文也回 `1`** | 停止重发 |
| `2` | 消息有误 | 报文非法 JSON、缺 `msgId`、缺身份字段、业务校验失败 | 按 8.1 重发 |
| `3` | 不支持 | `mqttType` 未登记，或该类型本平台不消费 | **不要重发**（重发也无意义） |

**回 ACK 的判定规则**（`MqttType.needAck()`）：

```
需要回 ACK  ⟺  该类型方向为 /up  且  字典中登记了回复类型  且  收到的是已知 mqttType
```

因此：`low-state`、`low-base`、`accident-media-resp` **不需要回复**（QoS 0 或本身即应答），
本平台收到后不发送任何 ACK。

#### 3.4.2 重发策略（上游存在两处口径，本平台并存支持）

上游文档对重发有两处不同描述，且 8.1 节自身表述矛盾（「至少发送 5 次」与「已重发满 3 次时停止」）：

| 来源 | 触发条件 | 间隔 | 次数上限 |
|---|---|---|---|
| 5.1.4.1 / 6.1.4.1 | 未收到回复（未确认） | 5 秒 | 3 次 |
| 8.1 | 收到**异常**回复（`res` = 2 或 3） | 3 秒 | 5 次 |

本平台**取更保守的并集**（两个场景分别使用各自策略），配置项如下：

| 配置项 | 默认 | 说明 |
|---|---|---|
| `dssad.mqtt.retry.confirm-interval-millis` | `5000` | 未确认重发间隔 |
| `dssad.mqtt.retry.confirm-max-attempts` | `3` | 未确认重发上限 |
| `dssad.mqtt.retry.invalid-interval-millis` | `3000` | 异常回复重发间隔 |
| `dssad.mqtt.retry.invalid-max-attempts` | `5` | 异常回复重发上限 |

#### 3.4.3 ACK 时序（以事故上报为例）

```
车端                          监管平台云端                      DSSAD 云平台
 │                                │                              │
 │── accident/up (QoS1) ─────────►│                              │
 │                                │── accident/up ──────────────►│ (本平台订阅车云上行)
 │                                │                              ├─ 解析 Topic
 │                                │                              ├─ 校验 msgId / vin
 │                                │                              ├─ 幂等去重（msgId）
 │                                │                              ├─ 入库 + 触发媒体请求
 │◄──────── accident-resp/down ───│◄── accident-resp (res=1) ────┤
 │  (收到 res=1 → 停止重发)        │                              │
 │                                │                              │
 │                                │◄── accident-media/down ──────┤ (本平台发起视频请求)
 │◄──────── accident-media/down ──│                              │  (含 eventId + 方向数组)
 │                                │                              │
 │── accident-media-resp/up ─────►│── accident-media-resp ──────►│ (受理确认，无需 ACK)
 │                                │                              │
 │── HTTP multipart 上传视频 ───────────────────────────────────►│ (POST /manager/api/v1/video/
 │                                │                              │       accident-media-upload)
 │◄───────────────────────────────────────────────── code=0000 ─┤
```

**关键点**：媒体请求的**触发时机**是平台**成功入库事故后**，而不是等 ACK 往返完成——
避免上游 ACK 延迟拖慢取证链路（取证有时间窗要求：事发前 15 秒 + 后 5 秒的视频需尽快落盘）。

#### 3.4.4 回复 Topic 的构造（镜像 + 回退）

回复报文的 Topic 遵循**镜像原则**：身份信息取自请求报文，类型与方向取自回复类型。

```
请求：uvodp/low_speed/LSV9123456789012/inh/up
回复：uvodp/low_speed/LSV9123456789012/inh-resp/down
```

当请求 Topic **缺少**必要身份段时（典型是 `groute` 云云 → 车云回复），回退顺序为：

| 缺失段 | 回退来源 1 | 回退来源 2 |
|---|---|---|
| `vin` | 报文体中的 `vin` 字段 | —（无则抛异常，不发送到错误主题） |
| `vehicleType` | 请求 Topic | **默认 `low_speed`**（见 7.3 节 D-01） |
| `enterpriseId` | 请求 Topic | 本平台配置 `dssad.enterprise-id` |

### 3.5 上行报文定义

上行的公共字段（除特别说明外，所有上行报文均含）：

| 字段 | 类型 | 必需 | 说明 |
|---|---|---|---|
| `vin` | String | 车云必需 | 车辆 VIN |
| `enterpriseId` | String | 云云必需 | 企业 ID（云云报文中可与 `vin` 同时出现） |
| `msgId` | String | **是** | 消息标识，发送方生成的 UUID，用于请求-响应配对与幂等去重 |
| `timestamp` | Long | 是 | 13 位毫秒时间戳 |

> 校验规则（`MqttMessageRouter.validate`）：车云报文缺 `vin` → `res=2`；
> 云云报文缺 `enterpriseId` **且** 缺 `vin` → `res=2`；需回复的上行报文缺 `msgId` → `res=2`（无法配对，必须拒绝）。

#### 3.5.1 车云上行

**① `inh` 车辆准静态参数信息**

| 项 | 值 |
|---|---|
| Topic | `uvodp/{vehicleType}/{vin}/inh/up` |
| QoS | 1 |
| 触发时机 | 车端启动完成 MQTT 连接，或完成 MQTT 重连后 |
| 是否需要回复 | 是 → `inh-resp/down` |
| 本平台处理 | 落库为准静态参数快照，供「车辆档案」与参数变更比对（`GET /api/v1/vehicles/{vin}/static-params`） |

| 字段 | 类型 | 说明 | 枚举 / 备注 |
|---|---|---|---|
| `vin` | String | 车辆 VIN | 唯一编号 |
| `msgId` | String | 消息标识 | UUID |
| `timestamp` | Long | 时间戳 | `1736840005000` |
| `hwVersion` | String | 自动驾驶硬件版本 | 实现自动驾驶功能的相关硬件型号 |
| `adVersion` | String | 自动驾驶系统软件版本号 | — |
| `comType` | String | 蜂窝通讯类型 | `0` 未知 / `1` 4G / `2` 5G / `3` 5GA / `4` 6G；缺省 `0` |
| `timeSync` | String | 时间同步方式 | `0` 无法获取 / `1` 不支持 / `2` NTP / `3` GNSS / `4` GNSS+NTP / `5` 其他；缺省 `5` |

```json
{
  "vin": "LSVT9123456789012",
  "msgId": "4bfcf76e-29d1-59c7-89e3-0eee90c9a7e6",
  "timestamp": 1736840005000,
  "hwVersion": "xxxxx",
  "adVersion": "xxxxx",
  "comType": "2",
  "timeSync": "3"
}
```

**② `low-state` 车辆低频运行状态信息**

| 项 | 值 |
|---|---|
| Topic | `uvodp/{vehicleType}/{vin}/low-state/up` |
| QoS | **0** |
| 触发时机 | 连接 MQTT 后，**间隔 60 秒**发送 1 次 |
| 是否需要回复 | **否** |
| 本平台处理 | 更新车辆实时状态（内存最新值 + 时序落库），供 `GET /api/v1/vehicles/{vin}/state` |

| 字段 | 类型 | 说明 | 单位 / 枚举 |
|---|---|---|---|
| `gnssStatus` | String | 定位状态 | `0` 缺省 GNSS 无效 / `1` 单点定位 / `2` 差分 / `3` 无效 PPS / `4` 固定解 / `5` 浮点解 / `6` 正在估算 / `7` 人工输入固定值 / `8` 模拟模式 / `9` WAAS 差分 / `10` GNSS+IMU / `11` DGPS+IMU / `12` RTK+IMU / `13` RTK+IMU+视觉 |
| `vehicleTotalWeight` | Double | 实时总质量（含自身、乘客、货物） | kg，`0` 表示未知 |
| `tirePressure` | Double[N] | 胎压数组，N = 车轮数 | kPa；顺序：**从车头开始从左到右**（前左1, 前左2, 前右1, 前右2, 中左1, …, 后右2） |
| `endurance` | Double | 预计续航 | km |
| `mileage` | Double | 里程表读数 | km |
| `soc` | Double | 电池剩余电量 | % |
| `batteryCapacity` | Double | 电池容量 | 度 |
| `temperature` | Integer | 电池温度 | ℃，**数据偏移量 100**：实际值 = `temperature - 100`，范围 -100℃ ~ 100℃ |
| `chargeState` | String | 充电状态 | `0` 缺省 / `1` 未充电 / `3` 正在充电 / `4` 充电故障 |

```json
{
  "vin": "LSVT9123456789012",
  "msgId": "4bfcf76e-29d1-59c7-89e3-0eee90c9a7e6",
  "timestamp": 1736840005000,
  "gnssStatus": "4",
  "vehicleTotalWeight": 123,
  "tirePressure": [2.4, 2.5, 2.5, 2.5],
  "endurance": 246.6,
  "mileage": 12342,
  "soc": 14.5,
  "batteryCapacity": 100,
  "temperature": 70,
  "chargeState": "1"
}
```

> **`temperature: 70` 的实际含义是 -30℃**（70 - 100）。本平台的接口响应会额外给出
> `temperatureCelsius: -30`，见 2.5.3 与 6.3.3。

**③ `low-base` 车辆低频基础信息**

| 项 | 值 |
|---|---|
| Topic | `uvodp/{vehicleType}/{vin}/low-base/up` |
| QoS | **0** |
| 触发时机 | 连接 MQTT 后，**间隔 1 秒**发送 1 次 |
| 是否需要回复 | **否** |
| 本平台处理 | 写入轨迹点表（1Hz 主数据源），供轨迹回放与抽稀（`GET /api/v1/vehicles/{vin}/track`） |
| 容量提示 | 1Hz 全量落库成本高（1000 台车 ≈ 8640 万条/天），生产环境需评估分区/保留策略 |

| 字段 | 类型 | 说明 | 单位 / 枚举 |
|---|---|---|---|
| `longitude` | Double | 经度 | 东经为正，西经为负（GCJ-02） |
| `latitude` | Double | 纬度 | 北纬为正，南纬为负（GCJ-02） |
| `altitude` | Double | 高程 | m（精度 0.01） |
| `speed` | Double | 速度 | km/h，保留 2 位小数 |
| `accSpeed` | Double | 加速度 | **0.01 m/s²**，保留 2 位小数 |
| `drivingMode` | Integer | 驾驶模式 | `0` 自动驾驶 / `1` 人工驾驶 / `2` 远程接管 |
| `heading` | Double | 航向角 | 正北为 0 度顺时针，精度 0.01 度 |

```json
{
  "vin": "LSVT9123456789012",
  "msgId": "4bfcf76e-29d1-59c7-89e3-0eee90c9a7e6",
  "timestamp": 1736840005000,
  "longitude": 104.06,
  "latitude": 30.67,
  "altitude": 500.25,
  "speed": 14,
  "accSpeed": 2.5,
  "drivingMode": 0,
  "heading": 90
}
```

**④ `accident` 车辆事故信息**

| 项 | 值 |
|---|---|
| Topic | `uvodp/{vehicleType}/{vin}/accident/up` |
| QoS | 1 |
| 触发时机 | 车端识别到发生事故时 |
| 是否需要回复 | 是 → `accident-resp/down` |
| 本平台处理 | 落库事故记录（状态 `REPORTED`）→ **立即下发 `accident-media/down` 索取视频** → 状态流转至 `MEDIA_REQUESTED` |

| 字段 | 类型 | 说明 | 备注 |
|---|---|---|---|
| `eventId` | String | 事故 ID | **车端生成**，后续媒体请求与上传均以此关联 |
| `accidentDesc` | String | 事故描述 | — |
| `currentPoint` | Double[2] | 事故发生位置 | **数组第一位是纬度，第二位是经度** |

```json
{
  "vin": "LJGTE25J582001234",
  "msgId": "4bfcf76e-29d1-59c7-89e3-0eee90c9a7e6",
  "timestamp": 1699200000000,
  "eventId": "xxxxxx",
  "accidentDesc": "xxxxxx",
  "currentPoint": [22.3213, 130.4342]
}
```

**⑤ `error-report` 车辆故障信息**

| 项 | 值 |
|---|---|
| Topic | `uvodp/{vehicleType}/{vin}/error-report/up` |
| QoS | 1 |
| 触发时机 | 车端识别到车辆故障时 |
| 是否需要回复 | 是 → `error-report-resp/down` |
| 本平台处理 | 展平 `errorData` 数组逐条落库；`errorCode` 关联附录 A 字典翻译为中文名与分类（见 7.1） |

| 字段 | 类型 | 说明 | 备注 |
|---|---|---|---|
| `errorNum` | Integer | 故障个数 | 应等于 `errorData` 长度 |
| `errorData` | Object[N] | 故障数据数组 | N = `errorNum` |
| `errorData[].errorCode` | String | 故障码 | 取值见附录 A.1，如 `4352` |
| `errorData[].errorTimestamp` | Long | 故障发生时间 | 检测到故障发生的时间戳 |
| `errorData[].errorSeverity` | String | 严重等级 | 见下表 |
| `errorData[].msgcount` | Integer | 累计上报次数 | 同一故障类别由**车端自计量**生成 |
| `errorData[].errorReason` | String | 故障原因 | 如「心跳数据解析错误」 |
| `currentPoint` | Double[2] | 当前坐标点 | 第一位纬度、第二位经度 |

**`errorSeverity` 等级语义**

| 取值 | 含义 |
|---|---|
| `0~1` | 无影响 |
| `2~3` | 影响功能，为系统故障可恢复 |
| `4~5` | 需人工处理 |
| `6~7` | 严重或不可恢复故障 |

> **前端口径提醒**：「严重故障」统计口径为 `severity ≥ 6`（可配置），
> 与「需人工处理」(`≥4`) 是两个不同的筛选条件，不要混用。

```json
{
  "vin": "LHGCM82633A654321",
  "msgId": "4bfcf76e-29d1-59c7-89e3-0eee90c9a7e6",
  "timestamp": 1736840005000,
  "errorNum": 2,
  "errorData": [
    {
      "errorCode": "4352",
      "errorTimestamp": 1736840005000,
      "errorSeverity": "1",
      "msgcount": 3,
      "errorReason": "电池系统故障"
    },
    {
      "errorCode": "4353",
      "errorTimestamp": 1736840005000,
      "errorSeverity": "1",
      "msgcount": 3,
      "errorReason": "电机系统故障"
    }
  ],
  "currentPoint": [22.3213, 130.4342]
}
```

**⑥ `accident-media-resp` 车端事故媒体数据请求回复**

| 项 | 值 |
|---|---|
| Topic | `uvodp/{vehicleType}/{vin}/accident-media-resp/up` |
| QoS | 1 |
| 触发时机 | 车端收到 `accident-media/down` 后应答 |
| 是否需要回复 | **不需要**（该报文本身即应答，不再触发二次回复） |
| 语义 | 表示「已受理，即将通过 HTTP 上传视频」 |
| 本平台处理 | 更新事故状态 `MEDIA_REQUESTED → MEDIA_ACCEPTED`，记录受理时间，用于取证超时催办（`GET /api/v1/events/accidents/pending`） |

| 字段 | 类型 | 说明 |
|---|---|---|
| `vin` | String | 车辆 VIN |
| `msgId` | String | 车端本消息编号（UUID） |
| `ackMsgId` | String | **回复消息标识**：回填平台下发 `accident-media/down` 时的 `msgId` |
| `timestamp` | Long | 时间戳 |
| `res` | String | 消息接收标志：`1` 确认 / `2` 消息有误 / `3` 不支持 |

> ✔️ 上游文档 5.1.4.1.1 **仅给出了数据示例，未给出字段表**。上表字段由示例反推，已验证与实现一致。

```json
{
  "vin": "LSVT9123456789012",
  "msgId": "4bfcf76e-29d1-59c7-89e3-0eee90c9a7e6",
  "ackMsgId": "8yhdf76e-29d1-59c7-89e3-0eee90c9a7e6",
  "timestamp": 1736840005000,
  "res": "1"
}
```

#### 3.5.2 云云上行（本平台发布 → 监管平台）

> 这三个业务报文由**企业云端**发布。本平台通过 `POST /api/v1/operations/tasks`
> 与远驾舱位接口间接触发 `task` / `remote-driving` 的发布；`groute` 由车端经企业侧通道上报后转发。

**⑦ `remote-driving` 远程驾驶接管信息**

| 项 | 值 |
|---|---|
| Topic | `uvodp/{enterpriseId}/remote-driving/up` |
| QoS | 1 |
| 触发时机 | 企业云端向无人车发起 / 结束远程驾驶接管时，**同时**向监管平台发送 |
| 是否需要回复 | 是 → `remote-driving-resp/down` |
| 本平台触发入口 | `POST /api/v1/operations/vehicles/{vin}/cockpit/start`、`.../cockpit/end` |

| 字段 | 类型 | 必需 | 说明 | 备注 |
|---|---|---|---|---|
| `enterpriseId` | String | 是 | 企业 ID | 企业唯一编号 |
| `vin` | String | 是 | 车辆 VIN | 唯一编号 |
| `msgId` | String | 是 | 消息标识 | 企业云端消息编号，UUID |
| `timestamp` | Long | 是 | 时间戳 | `1736840010000` |
| `type` | String | 是 | 接管类型 | `1` 发起接管 / `2` 结束接管 |
| `driver` | String | 发起时必传 | 驾驶员姓名 | — |
| `card` | String | 发起时必传 | 驾驶证号 | — |
| `remoteCockpitNo` | String | 发起时必传 | 台架号（远程驾驶舱编号） | — |

```json
{
  "enterpriseId": "xxxxxxxx",
  "vin": "LHGCM82633A123456",
  "msgId": "4bfcf76e-29d1-59c7-89e3-0eee90c9a7e6",
  "timestamp": 1736840010000,
  "type": "1",
  "driver": "张三",
  "card": "510123199001011234",
  "remoteCockpitNo": "YK2025001"
}
```

> ⚠️ **敏感信息提示**：`card`（驾驶证号）属个人敏感信息。本平台的
> `GET /api/v1/operations/remote-driving` 响应中该字段**已打码**（如 `510123********1234`），
> 明文仅在 MQTT 上行报文中传输，且链路要求 TLS 1.2+。

**⑧ `task` 任务信息**

| 项 | 值 |
|---|---|
| Topic | `uvodp/{enterpriseId}/task/up` |
| QoS | 1 |
| 触发时机 | 企业云端向无人车下发任务 / 结束任务时，**同时**向监管平台发送 |
| 是否需要回复 | 是 → `task-resp/down` |
| 本平台触发入口 | `POST /api/v1/operations/tasks`（body 见 6.5.2） |

| 字段 | 类型 | 必需 | 说明 | 备注 |
|---|---|---|---|---|
| `enterpriseId` | String | 是 | 企业 ID | — |
| `vin` | String | 是 | 车辆 VIN | — |
| `msgId` | String | 是 | 消息标识 | UUID |
| `timestamp` | Long | 是 | 数据上报时间 | Unix 毫秒时间戳 |
| `taskAction` | String | 是 | 任务动作 | `1` 开始 / `2` 结束 |
| `taskId` | String | 是 | 任务 ID | 结束任务时**必须复用**开始时的 `taskId` |
| `taskStartPosition` | String | 开始时必需 | 任务起点 | 文本描述 |
| `taskStartPoint` | Double[2] | 开始时必需 | 任务起点坐标 | 第一位纬度、第二位经度 |
| `taskEndPosition` | String | 开始时必需 | 任务终点 | 文本描述 |
| `taskEndPoint` | Double[2] | 开始时必需 | 任务终点坐标 | 第一位纬度、第二位经度 |

> ⚠️ **`taskId` 复用是硬要求**：结束任务时若 `taskId` 与开始时不匹配，
> 平台无法完成配对，会产生 `ORPHAN_FINISH`（孤儿结束记录）。
> 本平台会将该类记录在 `GET /api/v1/operations/tasks` 中**单独标记**，
> 前端已做红色告警展示；该字段在 `POST /api/v1/operations/tasks` 上标注为非空。

```json
{
  "enterpriseId": "xxxxxxxx",
  "vin": "LJGTE25J582000789",
  "msgId": "4bfcf76e-29d1-59c7-89e3-0eee90c9a7e6",
  "timestamp": 1736843605000,
  "taskAction": "1",
  "taskId": "TASK_20251105_001",
  "taskStartPosition": "科技园南门停车场",
  "taskStartPoint": [22.3213, 130.4342],
  "taskEndPosition": "中央商务区A座楼下",
  "taskEndPoint": [22.3213, 130.4342]
}
```

**⑨ `groute` 车端导航路径信息**

| 项 | 值 |
|---|---|
| Topic | `uvodp/{enterpriseId}/groute/up` |
| QoS | 1 |
| 触发时机 | 车端设置导航并将确认的导航路径点发送云端 |
| 是否需要回复 | 是 → `groute-resp/down`（⚠️ 回复 Topic 作用域不对称，见 3.3 与 D-01） |
| 本平台处理 | 落库导航路径（`GET /api/v1/operations/routes` / `/routes/latest`） |

| 字段 | 类型 | 说明 | 备注 |
|---|---|---|---|
| `pathPoints` | Double[Double[2]] | 导航点集合 | 首元素为起点，末元素为终点，中间为途经点与路径点；每点第一位纬度、第二位经度 |
| `currentPoint` | Double[2] | 事件发生时位置 | 第一位纬度、第二位经度 |

```json
{
  "enterpriseId": "xxxxxxxx",
  "vin": "LSVT9123456789012",
  "timestamp": 1736840005000,
  "pathPoints": [
    [29.512339, 106.324345],
    [29.812339, 106.924345],
    [29.312339, 106.124345]
  ],
  "currentPoint": [29.312339, 106.124345]
}
```

> ✔️ 上游 6.1.4.2.3 的**数据示例未包含 `msgId`**，但字段表中定义了该字段且本平台需要用它回 ACK。
> 本平台按字段表要求 `msgId`；缺失时回 `res=2`。该差异登记为 D-03。

**⑩ `mapehn-resp` 地图增强信息回复**

| 项 | 值 |
|---|---|
| Topic | `uvodp/{enterpriseId}/mapehn-resp/up` |
| QoS | 1 |
| 触发时机 | 本平台收到 `mapehn/down` 并成功入库后应答 |
| 是否需要回复 | 否 |
| 本平台处理 | 自动回复 `res=1`；若 `barrierId` 缺失等业务校验失败则回 `res=2` |

| 字段 | 类型 | 说明 |
|---|---|---|
| `enterpriseId` | String | 企业 ID |
| `msgId` | String | 本平台生成的消息编号（UUID） |
| `ackMsgId` | String | 回填平台下发 `mapehn/down` 的 `msgId` |
| `timestamp` | Long | 时间戳 |
| `res` | String | `1` 确认 / `2` 消息有误 / `3` 不支持 |

### 3.6 下行报文定义

#### 3.6.1 通用回复报文（`*-resp/down`）

上游 5.1.5.1.x 与 6.1.5.1.x 定义的回复报文**结构完全一致**，差别仅在于云云场景多一个 `enterpriseId`：

| 字段 | 类型 | 车云 | 云云 | 说明 |
|---|---|---|---|---|
| `enterpriseId` | String | — | ✔️ | 企业 ID |
| `vin` | String | ✔️ | ✔️ | 车辆 VIN |
| `msgId` | String | ✔️ | ✔️ | **回复方新生成**的消息编号（UUID） |
| `ackMsgId` | String | ✔️ | ✔️ | **回填请求方的 `msgId`**，用于配对与去重 |
| `timestamp` | Long | ✔️ | ✔️ | 毫秒时间戳 |
| `res` | String | ✔️ | ✔️ | `1` 确认 / `2` 消息有误 / `3` 不支持 |

**覆盖的 6 个类型**

| # | mqttType | Topic | 回复对象 |
|---:|---|---|---|
| ⑪ | `inh-resp` | `uvodp/{vehicleType}/{vin}/inh-resp/down` | `inh/up` |
| ⑫ | `accident-resp` | `uvodp/{vehicleType}/{vin}/accident-resp/down` | `accident/up` |
| ⑬ | `error-report-resp` | `uvodp/{vehicleType}/{vin}/error-report-resp/down` | `error-report/up` |
| ⑭ | `remote-driving-resp` | `uvodp/{enterpriseId}/remote-driving-resp/down` | `remote-driving/up` |
| ⑮ | `task-resp` | `uvodp/{enterpriseId}/task-resp/down` | `task/up` |
| ⑯ | `groute-resp` | `uvodp/{vehicleType}/{vin}/groute-resp/down` ⚠️ | `groute/up` |

```json
{
  "vin": "LSVT9123456789012",
  "msgId": "4bfcf76e-29d1-59c7-89e3-0eee90c9a7e6",
  "ackMsgId": "8yhdf76e-29d1-59c7-89e3-0eee90c9a7e6",
  "timestamp": 1736840005000,
  "res": "1"
}
```

> **回复报文的双向语义**：本平台既**发送**回复（应答对端上行），也**接收**回复
> （上游应答本平台发出的 `task`/`remote-driving`/`groute`/`mapehn-resp` 上行）。
> 接收侧的处理规则见 3.4.3：`res=1` 关闭重发登记；`res=2/3` 切换为异常重发策略。

#### 3.6.2 `accident-media` 车端事故媒体数据请求

| 项 | 值 |
|---|---|
| Topic | `uvodp/{vehicleType}/{vin}/accident-media/down` |
| QoS | 1 |
| 方向 | **平台 → 车端**（本平台为发起方） |
| 触发时机 | 本平台收到并成功入库 `accident/up` 后，**立即**发起 |
| 是否需要回复 | 车端以 `accident-media-resp/up` 应答（上游未定义本类型的回复主题，故本平台**不等待** ACK） |
| 视频规格 | 事发时间点**前 15 秒 + 后 5 秒**，车端前后左右**至少 4 个方向** |

| 字段 | 类型 | 说明 | 备注 |
|---|---|---|---|
| `vin` | String | 车辆 VIN | — |
| `msgId` | String | 消息标识 | **云端消息编号 UUID**，车端上传时须以 `ackMsgId` 回填此值 |
| `timestamp` | Long | 时间戳 | — |
| `eventId` | String | 事故 ID | 车端此前上报的 `eventId` |
| `cameraDirection` | String[N] | 摄像头位置数组 | `1` 前向 / `2` 右向 / `3` 后向 / `4` 左向 |

```json
{
  "vin": "LJGTE25J582001234",
  "msgId": "4bfcf76e-29d1-59c7-89e3-0eee90c9a7e6",
  "timestamp": 1699200000000,
  "eventId": "xxxxx",
  "sensorDirection": ["1", "2", "3", "4"]
}
```

> ⚠️ **上游文档自相矛盾**：正文**字段表**写的是 `cameraDirection`，
> 而同节的**数据示例**写的是 `sensorDirection`。本平台**两个字段都接收**
> （优先 `cameraDirection`，为空则回落到 `sensorDirection`），
> 发送时**统一使用字段表定义的 `cameraDirection`**。登记为 D-02。

#### 3.6.3 `mapehn` 地图增强信息

| 项 | 值 |
|---|---|
| Topic | `uvodp/{enterpriseId}/mapehn/down` |
| QoS | 1 |
| 方向 | 平台 → 企业云端 / 车端（**本平台为接收方**） |
| 是否需要回复 | **是** → `mapehn-resp/up`（唯一一个下行也需回复的类型） |
| 本平台处理 | 落库交通阻断设施；`GET /api/v1/map-barriers` 提供当日视图与历史回看 |

| 字段 | 类型 | 说明 | 备注 |
|---|---|---|---|
| `enterpriseId` | String | 企业 ID | — |
| `msgId` | String | 消息标识 | 云端唯一编号 UUID |
| `timestamp` | Long | 时间戳 | — |
| `areaCode` | String | 区域编码 | — |
| `barrierType` | String | 交通阻断类型 | `20` 禁行区域 / `21` 限速区域 |
| `barrierName` | String | 交通阻断名称 | 如「学校区域」 |
| `barrierId` | String | 编号 | 阻断设施唯一标识 |
| `barrierPolygon` | Double[Double[]] | 阻断区域多边形顶点坐标数组 | **首尾为相同点形成闭环**；**单点时数组内只有 1 个坐标** |
| `dateType` | String | 时间段类型 | `1` 工作日 / `2` 节假日 / `3` 每日 / `4` 周六周日 |
| `timeRange` | String[String[]] | 时间段集合 | 如 `[["7:00","9:00"], ["17:00","19:30"]]` |
| `speedLimit` | Double | 限速值 | **仅 `barrierType=21` 时有意义** |

```json
{
  "enterpriseId": "xxxxxxxx",
  "msgId": "4bfcf76e-29d1-59c7-89e3-0eee90c9a7e6",
  "timestamp": 1736840005000,
  "areaCode": "xxxx",
  "barrierType": "21",
  "barrierName": "学校区域",
  "barrierId": "xxxxxxxx",
  "barrierPolygon": [
    [29.512339, 106.324345],
    [29.812339, 106.924345],
    [29.512339, 106.324345]
  ],
  "dateType": "1",
  "timeRange": [["7:00", "9:00"], ["17:00", "19:30"]],
  "speedLimit": 25.0
}
```

> **多边形绘制注意**：`barrierPolygon` 中每个点是 `[纬度, 经度]`。
> 本平台前端在绘制时将其投影到本地米平面（纬度按 `110540 m/度`、经度按
> `111320 × cos(纬度) m/度` 换算），以保证**横纵比例真实**；
> 不接第三方地图 SDK（合规：GCJ-02 数据不得随意叠加到其他坐标系底图上）。

### 3.7 离线补传与优先级

#### 3.7.1 断线重连策略（上游 8.2）

| 项 | 约定 | 本平台配置 |
|---|---|---|
| 首次重连 | 立即 | — |
| 后续重连 | 指数退避 `2s → 4s → 8s → 16s`，上限 60s | `dssad.mqtt.reconnect.initial-backoff-millis = 2000`<br>`max-backoff-millis = 60000`<br>`multiplier = 2.0` |
| 持久化会话 | `clean_session = false` | `dssad.mqtt.clean-session = false` |
| 本地缓存 | 离线期间数据缓存在本地，重连后上传 | 入站队列 + 离线队列 |

#### 3.7.2 补传与丢弃优先级（上游 8.4）

离线队列满时按优先级**丢弃最低优先级**数据，补传顺序为：

```
事件数据（EVENT） > 基础数据（BASE） > 状态数据（STATE）
```

| 优先级 | 包含类型 | 理由 |
|---|---|---|
| **EVENT** | `accident`、`error-report`、`accident-media-resp`、`remote-driving`、`task` | 监管取证与安全事件，丢失不可恢复 |
| **BASE** | `inh`、`groute`、`mapehn-resp` | 静态/半静态信息，可重新获取 |
| **STATE** | `low-state`、`low-base` | 高频时序数据，丢失只影响曲线连续性 |

队列容量：`dssad.mqtt.offline-cache-size = 10000`（条）。

> ⚠️ **上游 8.4 的「车辆状态：保留最新 10 条」是针对车端本地缓存的要求**，不是平台侧要求。
> 本平台作为云端保留全量（受 `dssad.audit.retention-days` 与存储容量约束）。

### 3.8 平台侧处理流水线

`MqttMessageRouter` 是报文进入业务世界的**唯一入口**，把「所有类型都必须做」的横切逻辑收敛在一处：

```
报文到达
 ├─ 1. 解析 Topic ─────────── 失败 → 丢弃 + 留痕（status=INVALID）
 ├─ 2. 回复类下行 ─────────── res=1 关闭重发登记；res=2/3 切换异常重发策略
 ├─ 3. 查找处理器 ─────────── 无处理器 → 上行则回 res=3「不支持」+ 留痕
 ├─ 4. 通用协议校验 ───────── JSON 结构 / msgId / 发送方标识 / timestamp
 │                            失败 → 回 res=2 + 留痕
 ├─ 5. 幂等去重 ───────────── 重复报文（senderId + msgId）→ 跳过业务处理，**仍回 res=1**
 ├─ 6. 执行处理器 ─────────── 业务异常 → 回 res=2 + 留痕（含错误原因）
 ├─ 7. 回复 ACK ───────────── 仅对「需要回复的上行报文」生效
 └─ 8. 留痕 ──────────────── 报文原文 + 处理结果 + 耗时 + 时钟偏差
```

**两条关键设计决策**（排障时经常被问到）：

1. **幂等重复报文「照常回 ACK」**：对端重发的原因正是「没收到 ACK」。
   若重复报文不回 ACK，对端会一直重发到上限后才放弃，既浪费带宽也污染告警。

2. **单条脏数据不会拖垮消费线程**：`route()` 方法**不抛异常**；
   JSON 解析失败降级为 `payload = null` 并在 Router 层统一回 `res=2`，
   避免在 MQTT 回调线程中抛异常导致后续报文全部不被处理。

**留痕模式**（`dssad.audit.mode`，三档按存储预算选择）：

| 模式 | 行为 | 适用 |
|---|---|---|
| `full`（默认） | 全量落库 | 监管取证要求严格、车端规模较小 |
| `sampled` | 按 `sample-rate`（默认 `0.05`）采样 | 大规模车队，需控制存储成本 |
| `event-only` | 仅落事件类报文 | 存储预算有限但必须保留证据链 |

留存期 `dssad.audit.retention-days = 180`，超期由定时任务清理。

---

## 4. 企业云端 HTTP 接口（对外，监管平台调用）

### 4.1 基础信息

| 项 | 值 |
|---|---|
| Base URL | `https://{企业云端地址}/enterprise/api/v1` |
| Content-Type | `application/json`（请求与响应） |
| Method | **全部为 POST** |
| TLS | 1.2+ |
| 鉴权 | HMAC-SHA256 签名，4 个请求头（见 2.4.2） |
| 响应体 | 统一 `{code, message, data}`，成功码 `0000` |
| CORS | 仅开放 `POST`（对外接口不面向浏览器） |

**接口清单**

| # | Method | Path | 说明 | 上游章节 | 实现度 |
|---:|---|---|---|---|---|
| 1 | POST | `/enterprise/api/v1/video/live` | 实时视频地址获取 | 6.3.2.1.1 | ✅ |
| 2 | POST | `/enterprise/api/v1/video/history` | 历史视频获取 | 6.3.2.1.2 | ✅ |
| 3 | POST | `/enterprise/api/v1/event/remote-cockpit-start` | 远程驾驶接管发起 | 6.3.2.2.1 | ✅ |
| 4 | POST | `/enterprise/api/v1/event/remote-cockpit-end` | 远程驾驶接管结束 | —（**上游未定义，企业侧补充**） | ➕ |

### 4.2 POST /enterprise/api/v1/video/live — 实时视频地址获取

**请求体**

| 字段 | 类型 | 必需 | 说明 |
|---|---|---|---|
| `vin` | String | 是 | 车辆 VIN |
| `msgId` | String | 否 | 消息标识（云端消息编号，UUID） |
| `cameraDirection` | String[N] | **是** | 摄像头位置数组：`1` 前向 / `2` 右向 / `3` 后向 / `4` 左向 |
| `type` | String | 否 | 视频流类型：`1` m3u8 / `2` flv |

```json
{
  "vin": "LJGTE25J582001234",
  "msgId": "4bfcf76e-29d1-59c7-89e3-0eee90c9a7e6",
  "cameraDirection": ["1", "2", "3", "4"],
  "type": "1"
}
```

**响应 `data`**

| 字段 | 类型 | 说明 |
|---|---|---|
| `vin` | String | 车辆 VIN |
| `video` | Object[N] | 各方向实时视频拉流地址 |
| `video[].cameraDirection` | String | 摄像头位置 |
| `video[].url` | String | 拉流地址（由 SRS 提供） |
| `video[].type` | String | 视频流类型 |

```json
{
  "code": "0000",
  "message": "成功",
  "data": {
    "vin": "LJGTE25J582001234",
    "video": [
      { "cameraDirection": "1", "url": "http://127.0.0.1:8080/live/LJGTE25J582001234_1.m3u8", "type": "1" },
      { "cameraDirection": "2", "url": "http://127.0.0.1:8080/live/LJGTE25J582001234_2.m3u8", "type": "1" }
    ]
  }
}
```

**错误场景**

| 场景 | code |
|---|---|
| `vin` 为空、`cameraDirection` 为 `null` | `1001` |
| 签名相关请求头缺失 | `2001` |
| 签名不匹配 / 时间偏差超限 / Nonce 重放 | `1002` |
| 触发限流 | `4001` |
| 流媒体服务不可用 | `5003` |

> **前置条件**：车端需已建立实时流推送到 SRS。若车端未推流，接口仍返回 `0000`，
> 但 `url` 拉不到内容——这是 SRS 的固有行为（地址是「可访问的入口」而非「已就绪的流」）。

### 4.3 POST /enterprise/api/v1/video/history — 历史视频获取

**请求体**

| 字段 | 类型 | 必需 | 说明 |
|---|---|---|---|
| `vin` | String | 是 | 车辆 VIN |
| `msgId` | String | 否 | 消息标识 |
| `cameraDirection` | String | **是** | 摄像头位置（**单个方向**，与实时视频的数组不同） |
| `startTime` | String | 是 | 开始时间，格式 **`yyyy-MM-dd HH:mm:ss`** |
| `endTime` | String | 是 | 结束时间，格式 **`yyyy-MM-dd HH:mm:ss`** |

```json
{
  "vin": "LJGTE25J582001234",
  "msgId": "4bfcf76e-29d1-59c7-89e3-0eee90c9a7e6",
  "cameraDirection": "1",
  "startTime": "2026-03-10 00:00:00",
  "endTime": "2026-03-13 00:00:00"
}
```

**响应 `data`**

| 字段 | 类型 | 说明 |
|---|---|---|
| `vin` | String | 车辆 VIN |
| `video` | Object[N] | 时间段内的多个视频文件下载地址 |
| `video[].cameraDirection` | String | 摄像头位置 |
| `video[].url` | String | 下载地址 |
| `video[].type` | String | 文件类型（`1` mp4） |
| `video[].startTime` | String | 该文件开始时间 |
| `video[].endTime` | String | 该文件结束时间 |

```json
{
  "code": "0000",
  "message": "成功",
  "data": {
    "vin": "LJGTE25J582001234",
    "video": [
      {
        "cameraDirection": "1",
        "url": "http://127.0.0.1:8080/media/history/LJGTE25J582001234/1/2026-03-10.mp4",
        "type": "1",
        "startTime": "2026-03-10 00:00:00",
        "endTime": "2026-03-11 00:00:00"
      }
    ]
  }
}
```

**约束与错误**

| 约束 | 值 | 违反时 |
|---|---|---|
| 时间格式 | `yyyy-MM-dd HH:mm:ss` | `1001`，message 会**明确指出字段名与实际值** |
| 单次查询最大跨度 | 168 小时（`dssad.api.history-video-max-span-hours`） | `1001` |

> **时间格式错误返回 `1001` 而不是 `9999`，是刻意设计**：格式错误属于「调用方传参错误」，
> 若返回 `9999`（系统内部错误），对端会按上游 8.3「HTTP 请求重试」策略反复重试同一个
> 不可能成功的请求。本平台在 Controller 显式捕获解析异常并转换为参数错误，错误码语义正确。

### 4.4 POST /enterprise/api/v1/event/remote-cockpit-start — 远程驾驶接管发起

**请求体**

| 字段 | 类型 | 必需 | 说明 |
|---|---|---|---|
| `vin` | String | 是 | 车辆 VIN |
| `starter` | String | 是 | 发起人：`1` 交巡警 / `2` 监管平台 |

```json
{
  "vin": "LJGTE25J582001234",
  "starter": "1"
}
```

**响应 `data`**

| 字段 | 类型 | 说明 |
|---|---|---|
| `vin` | String | 车辆 VIN |
| `remoteCockpitId` | String | 远驾舱 ID |
| `driver` | String | 远驾舱驾驶员姓名 |
| `identityNumber` | String | 身份证号 |
| `driverLicenseNumber` | String | 驾驶证编号 |
| `mobile` | String | 联系方式 |

```json
{
  "code": "0000",
  "message": "成功",
  "data": {
    "vin": "LJGTE25J582001234",
    "remoteCockpitId": "YK0001",
    "driver": "张三",
    "identityNumber": "5001xxxxxxxxxxxx",
    "driverLicenseNumber": "5001xxxxxxxxxxxx",
    "mobile": "13000000000"
  }
}
```

**副作用（重要）**

该接口**不只是查询**，它有三个副作用：

1. 从远驾舱资源池中按 **LRU 策略**分配一个空闲舱位；
2. 绑定该舱位与车辆（`bindVehicle`），使 `GET /api/v1/operations/vehicles/{vin}/cockpit` 可查到当前接管；
3. **向监管平台发布 `remote-driving/up`（`type=1`）**，完成合规上报。

因此该接口**不是幂等的**：重复调用会重复分配与上报。监管平台应保证一次接管只调用一次。

**远驾舱资源池**

舱位信息（`cockpitId` / `driver` / `identityNumber` / `driverLicenseNumber` / `mobile`）属于**企业侧运力资源**，
通过配置维护，生产环境可改为从远驾舱管理系统实时查询：

```yaml
dssad:
  remote-driving:
    cockpits:
      - cockpit-id: YK0001
        driver: 张三
        identity-number: "5001xxxxxxxxxxxx"
        driver-license-number: "5001xxxxxxxxxxxx"
        mobile: "13000000000"
      - cockpit-id: YK0002
        driver: 李四
```

配置为空时使用内置兜底资源，保证接口始终可用（不会因漏配而 500）。

### 4.5 POST /enterprise/api/v1/event/remote-cockpit-end — 远程驾驶接管结束 ➕

> ⚠️ **本接口在上游文档 6.3 中未定义**，属企业侧补充能力。
> 补充原因：监管平台发起接管后，企业侧需要有明确的「结束接管」动作，
> 否则监管侧看到的接管状态**永远处于进行中**（`closed=false`），
> 导致「活跃接管数」指标只增不减。

**请求体**：与 4.4 相同（`vin` + `starter`）

**响应 `data`**：`Boolean` — `true` 表示确实存在活跃接管并已结束；`false` 表示本就没有活跃接管（幂等）

```json
{ "code": "0000", "message": "成功", "data": true }
```

**副作用**：向监管平台发布 `remote-driving/up`（`type=2`），更新接管记录 `closed=true` 并记 `endedAt`。

---

## 5. 车端媒体上传接口（对车端）

### 5.1 POST /manager/api/v1/video/accident-media-upload

| 项 | 值 |
|---|---|
| Path | `/manager/api/v1/video/accident-media-upload`（与上游 5.2.2.2.1 一致） |
| Method | `POST` |
| Content-Type | `multipart/form-data` |
| 鉴权 | `ackMsgId` 业务态鉴权（见 2.4.3），**不走 HMAC 签名** |
| 大小限制 | ≤ 200 MB |
| 上游章节 | 5.2.2.2.1 |

> **为什么 Path 用 `/manager` 前缀而不符合本平台的其他接口风格**：
> 上游文档要求车端在「直连监管平台」与「经企业云端」两种部署形态下**共用同一份上传代码**，
> 因此 Path、Method、字段名全部逐字对齐，不做任何改造。

**请求参数（全部为 `multipart/form-data` 的 part）**

| 字段 | 类型 | 必需 | 说明 |
|---|---|---|---|
| `vin` | String | 是 | 车辆 VIN |
| `msgId` | String | 是 | 本次上传的消息编号（云端消息编号 UUID） |
| `ackMsgId` | String | 是 | **授权凭据**：平台下发 `accident-media/down` 时的 `msgId` |
| `eventId` | String | 是 | 事故 ID（车端生成、与 `accident/up` 中一致） |
| `cameraDirection` | String | 是 | 摄像头位置：`1` 前向 / `2` 右向 / `3` 后向 / `4` 左向 |
| `file` | File | 是 | 事故视频文件（H.264 编码） |

**响应 `data`**

| 字段 | 类型 | 说明 |
|---|---|---|
| `eventId` | String | 事故 ID |
| `cameraDirection` | String | 摄像头位置 |
| `mediaId` | Long | 媒体资产 ID |
| `url` | String | 对外可访问地址（企业侧可据此校验文件是否真的落盘） |
| `fileSize` | Long | 文件字节数 |
| `directionName` | String | 方向中文名（如「前向」） |

```json
{
  "code": "0000",
  "message": "成功",
  "data": {
    "eventId": "xxxxx",
    "cameraDirection": "1",
    "mediaId": 1024,
    "url": "http://127.0.0.1:8080/media/accident/xxxxx/1.mp4",
    "fileSize": 8388608,
    "directionName": "前向"
  }
}
```

**错误场景**

| 场景 | code | message |
|---|---|---|
| `file` 为空或空文件 | `1001` | `file 不能为空` |
| 读取上传流失败（客户端中断） | `1001` | `读取上传文件失败：…` |
| `(vin, eventId)` 无对应事故记录 | `3001` | 资源不存在 |
| `ackMsgId` 与平台下发的 `mediaRequestMsgId` 不一致 | `1001` | 授权凭据不匹配 |
| 该 `(eventId, cameraDirection)` 已归档 | `3002` | 资源已存在（避免覆盖已有证据） |
| 磁盘写入失败 / 超过 200MB | `5003` / `1003` | — |

### 5.2 车端取证完整流程（车端视角）

```
1. 车端识别到事故
   └─ 保存事发【前 15 秒 + 后 5 秒】、前后左右【4 个方向】的连续视频
   └─ 生成唯一 eventId（UUID）

2. 车端上报结构化数据
   └─ PUBLISH uvodp/{vehicleType}/{vin}/accident/up   (QoS 1)
   └─ WAIT   accident-resp/down，res=1 才停止重发

3. 车端接收平台媒体请求
   └─ SUBSCRIBE 收到 uvodp/{vehicleType}/{vin}/accident-media/down
   └─ 记录 msgId（后续作为 ackMsgId 上传）

4. 车端应答受理
   └─ PUBLISH uvodp/{vehicleType}/{vin}/accident-media-resp/up  (QoS 1)

5. 车端逐方向上传视频
   └─ POST /manager/api/v1/video/accident-media-upload
        vin / msgId / ackMsgId / eventId / cameraDirection / file
   └─ 对请求中的每个 cameraDirection 各调一次
```

**车端存储要求（上游 3.2）**：需在车端存储**不低于 10 天**的前后左右四方位行车记录视频，以保障监管单位调取取证。

### 5.3 事故取证状态机（平台侧）

平台据此跟踪取证闭环，并提供催办清单接口（`GET /api/v1/events/accidents/pending`）：

| 状态 | 含义 | 进入条件 | 后续 |
|---|---|---|---|
| `REPORTED` | 已上报，尚未发起媒体请求 | 收到 `accident/up` 并入库 | 平台**立即**下发 `accident-media/down` |
| `MEDIA_REQUESTED` | 已下发媒体请求 | `accident-media/down` 发布成功 | 等待车端受理 |
| `MEDIA_ACCEPTED` | 车端已受理 | 收到 `accident-media-resp/up` 且 `res=1` | 等待 HTTP 上传 |
| `MEDIA_RECEIVED` | 视频已归档 | 收到上传并成功落盘 | **终态**（闭环完成） |

**超时催办口径**：距事故上报时间超过 `timeoutMinutes`（默认 10 分钟，可调）仍未到达 `MEDIA_RECEIVED`，
即出现在 `accidents/pending` 清单中，供运营人员主动联系车端补传。

**四方向归档明细**：事故详情（`GET /api/v1/events/accidents/{eventId}`）会返回每个方向的归档情况，
**缺失的方向会被显式标出**——这比「只列出已收到的文件」更能暴露取证不完整的问题。

---

## 6. 平台管理 API（供 Web 控制台）

> **非对外接口**。全部位于 `/api/v1/**`，鉴权方式为 `X-Token` 请求头（见 2.4.1），
> 响应体同样为统一结构（见 2.2），分页接口同样返回 `PageResult`（见 2.6）。
> 本节按业务域组织，共 **47 个**接口。

### 6.1 认证（2 个）

#### 6.1.1 POST /api/v1/auth/login

| 项 | 值 |
|---|---|
| 鉴权 | **白名单**（无需令牌） |
| 请求体 | `{ "username": "admin", "password": "dssad@2026" }` |
| 响应 `data` | `token` / `username` / `usingDefaultCredential` / `notice` |

```json
{
  "code": "0000",
  "message": "成功",
  "data": {
    "token": "3f2a1b8c-...-9e7d",
    "username": "admin",
    "usingDefaultCredential": true,
    "notice": "当前使用开发默认口令，上生产前请通过环境变量 DSSAD_ADMIN_USER/DSSAD_ADMIN_PASSWORD 覆盖"
  }
}
```

| 规则 | 说明 |
|---|---|
| 默认口令 | `admin` / `dssad@2026`，**仅在本机未设置环境变量时生效** |
| 生产覆盖 | `DSSAD_ADMIN_USER` / `DSSAD_ADMIN_PASSWORD` 环境变量 |
| 比较方式 | 常量时间比较（抵御时序攻击） |
| 失败 | `2001`「账号或密码错误」 |
| 令牌有效期 | 12 小时 |

> ⚠️ **`usingDefaultCredential = true` 是生产环境的严重风险信号**。
> 前端顶栏在有该标志时常驻显示告警，提示运维必须尽快更换口令。

#### 6.1.2 POST /api/v1/auth/logout

| 项 | 值 |
|---|---|
| 鉴权 | `X-Token`（可选传入，缺省也返回成功） |
| 请求体 | 无 |
| 响应 `data` | `null` |
| 副作用 | 注销令牌（`TokenService.revoke`），此后该令牌立即失效 |

### 6.2 运营总览（4 个）

> 数据源：5 张表的独立 COUNT/聚合查询（**不使用多表 JOIN**），在内存中拼装。
> 缓存：`overview` TTL 5 秒；`trend` / `fault-categories` / `fault-vehicles` TTL 60 秒。

#### 6.2.1 GET /api/v1/dashboard/overview

无参数。返回全部总览卡片指标：

| 字段 | 类型 | 说明 |
|---|---|---|
| `vehicleTotal` | int | 车辆总数 |
| `vehicleOnline` | int | 在线车辆数 |
| `vehicleOffline` | int | 离线车辆数 |
| `vehicleOnlineRate` | double | 在线率（0~1 或百分比，`vehicleTotal=0` 时为 `0.0`） |
| `vehicleByType` | Object | 按车型计数，如 `{"low_speed": 80, "high_speed": 48}` |
| `accidentToday` | long | 今日事故数 |
| `accidentYesterday` | long | 昨日事故数（用于环比） |
| `accidentPendingMedia` | long | **待取证**事故数（`mediaStatus = REPORTED`） |
| `faultToday` | long | 今日故障数 |
| `faultSevereToday` | long | 今日**严重**故障数（`severity ≥ 6`） |
| `faultTopCodes` | Array | 近 7 天故障码 Top10（结构见 6.4.3） |
| `taskRunning` | long | 进行中任务数 |
| `taskToday` | long | 今日任务数 |
| `remoteDrivingToday` | long | 今日远驾接管次数 |
| `remoteDrivingActive` | long | **进行中**远驾接管数（`closed = false`） |
| `mapBarrierToday` | long | 今日地图阻断设施数 |
| `mqttRecords24h` | long | 24 小时报文留痕数 |
| `mqttInboundQueue` | int | 入站队列当前长度 |
| `mqttInboundDropped` | long | 入站队列累计丢弃数 |
| `mqttOfflineQueue` | int | 离线队列当前长度 |
| `mqttPendingAck` | int | 待确认（重发中）报文数 |
| `generatedAt` | long | 数据生成时间（毫秒时间戳） |

> **`generatedAt` 的用途**：总览接口有 5 秒缓存，前端轮询间隔应 ≥ 缓存 TTL
> （内置默认 10 秒），避免「轮询很快但数据不变」的无效请求。判断数据新鲜度用 `generatedAt`，
> 不要用客户端本地时间。

#### 6.2.2 GET /api/v1/dashboard/trend

| 参数 | 类型 | 默认 | 范围 | 说明 |
|---|---|---|---|---|
| `days` | int | `7` | 1 ~ 90（超范围自动截断） | 统计天数 |

响应 `data` 为按日期升序的数组：

| 字段 | 类型 | 说明 |
|---|---|---|
| `date` | String | 日期 `yyyy-MM-dd` |
| `accident` | long | 当日事故数 |
| `fault` | long | 当日故障数 |

```json
{
  "code": "0000", "message": "成功",
  "data": [
    { "date": "2026-09-16", "accident": 2, "fault": 11 },
    { "date": "2026-09-17", "accident": 0, "fault": 8 }
  ]
}
```

> ⚠️ 代码注释称该接口返回「事故数、故障数、**任务数、在线车辆数**」，但**实际只返回 `accident` 与 `fault`**。
> 以本表为准；差异已登记为 I-02。

#### 6.2.3 GET /api/v1/dashboard/fault-categories

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `days` | int | `7` | 统计天数（1 ~ 90） |

响应 `data`：`[{ "category": "硬件故障", "count": 42 }]`（分类取自附录 A.1 的 5 大类，由字典翻译）

#### 6.2.4 GET /api/v1/dashboard/fault-vehicles

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `days` | int | `7` | 统计天数（1 ~ 90） |
| `limit` | int | `10` | 返回条数 |

响应 `data`：`[{ "vin": "LSVT9123456789012", "count": 17 }]`

### 6.3 车辆（7 个）

#### 6.3.1 GET /api/v1/vehicles — 车辆列表（分页）

| 参数 | 类型 | 必需 | 默认 | 说明 |
|---|---|---|---|---|
| `keyword` | String | 否 | — | 关键字（VIN / 车牌模糊匹配） |
| `vehicleType` | String | 否 | — | `low_speed` / `high_speed` |
| `online` | Boolean | 否 | — | 在线筛选（**在线判定由后端按最后上报时间计算**） |
| `page` / `size` | int | 否 | 1 / 20 | 分页 |

响应 `data`：`PageResult<Vehicle>`

| Vehicle 字段 | 类型 | 说明 |
|---|---|---|
| `vin` | String | 车辆 VIN |
| `vehicleType` | String | 车型 |
| `plateNo` | String | 车牌号 |
| `enterpriseId` | String | 所属企业 |
| `lastSeenAt` | long | 最后上报时间（毫秒时间戳） |
| `online` | boolean | 在线状态 |
| `createdAt` / `updatedAt` | long | 创建 / 更新时间 |

> **`online` 为什么由后端算**：在线判定依赖「最后上报时间 + 阈值」这一服务端口径。
> 若交给前端计算，各端阈值不一致会出现「同一个车在列表页在线、在详情页离线」的诡异现象。

#### 6.3.2 GET /api/v1/vehicles/{vin} — 车辆详情

响应 `data` 为**嵌套结构**（注意：不是扁平字段）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `vehicle` | Object | 车辆基础信息（同 `Vehicle`） |
| `latestState` | Object | 最新运行状态（同 6.3.3 的 `LowState`），可能为 `null` |
| `position` | Object | 最新位置（同 6.3.4），可能为 `null` |

```json
{
  "code": "0000", "message": "成功",
  "data": {
    "vehicle":     { "vin": "LSVT9123456789012", "plateNo": "川A·12345", "...": "..." },
    "latestState": { "soc": 68.5, "temperature": 135, "temperatureCelsius": 35, "...": "..." },
    "position":    { "longitude": 104.06, "latitude": 30.67, "...": "..." }
  }
}
```

> ⚠️ **前端踩坑记录**：早期实现按**顶层**读取 `data.vin` / `data.soc`，
> 导致整个车辆档案显示 `—`、在线状态恒为「离线」——**不报错、只显示空**的静默失效。
> 已修正为 `data.vehicle` / `data.latestState` / `data.position`。
> 对接方务必按本表的三段式结构解析。

#### 6.3.3 GET /api/v1/vehicles/{vin}/state — 最新运行状态

响应 `data` 为 `LowState`（字段定义见 3.5.1 ②），**额外增加一个派生字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `temperature` | Integer | **协议原文**：实际摄氏度 + 100 |
| `temperatureCelsius` | Integer | **真值**：`temperature - 100`，单位 ℃ |

> 两个字段同时返回是刻意的防错设计：调用方无论按哪个名字取值都不会拿到错误结果，
> 从而消除「漏减 100 导致大屏显示电池温度 135℃」这类静默错误。

#### 6.3.4 GET /api/v1/vehicles/{vin}/position — 最新位置

| 字段 | 类型 | 说明 |
|---|---|---|
| `vin` | String | 车辆 VIN |
| `longitude` / `latitude` | Double | 经纬度（GCJ-02） |
| `altitude` | Double | 高程（m） |
| `speed` | Double | 速度（km/h） |
| `heading` | Double | 航向角（度） |
| `drivingMode` | Integer | 驾驶模式：`0` 自动 / `1` 人工 / `2` 远程接管 |
| `updatedAt` | long | 定位更新时间（毫秒时间戳） |

#### 6.3.5 GET /api/v1/vehicles/{vin}/state-history — 状态历史（分页）

| 参数 | 类型 | 默认 | 范围 | 说明 |
|---|---|---|---|---|
| `hours` | int | `24` | 1 ~ 2160 | 回看小时数 |
| `page` / `size` | int | 1 / 50 | — | 分页 |

响应 `data`：`PageResult<LowState>`

#### 6.3.6 GET /api/v1/vehicles/{vin}/track — 轨迹回放（**抽稀**）

| 参数 | 类型 | 必需 | 默认 | 说明 |
|---|---|---|---|---|
| `startTime` | long | **是** | — | 起始毫秒时间戳 |
| `endTime` | long | **是** | — | 结束毫秒时间戳 |
| `maxPoints` | int | 否 | `3000` | 抽稀后最大点数 |

响应 `data`（`TrackResult`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `vin` | String | 车辆 VIN |
| `points` | Array | 抽稀后的轨迹点 |
| `points[].timestamp` | long | 时间戳 |
| `points[].longitude` / `latitude` | Double | 经纬度 |
| `points[].speed` | Double | 速度 |
| `points[].heading` | Double | 航向角 |
| `points[].drivingMode` | Integer | 驾驶模式 |
| `rawCount` | int | 抽稀前点数 |
| `returnedCount` | int | 抽稀后点数 |
| `tolerance` | double | 使用的抽稀容差（米，默认 8.0） |
| `simplifyRatio` | String | 形如 `"3000 / 86400"`，便于前端直接展示 |

> **为什么必须抽稀**：`low-base` 是 1Hz 上报，24 小时 = 86400 个点。
> 直接返回会撑爆浏览器（渲染卡死）与网络。抽稀容差 8 米（`dssad.api.track-simplify-tolerance`），
> 在视觉无感的前提下把点数压到 3000 以内；`rawCount` / `returnedCount` 同时返回，
> 便于判断「当前视图是否因抽稀而丢失细节」——需要细节时缩小时间窗重新查询，而不是调大 `maxPoints`。

#### 6.3.7 GET /api/v1/vehicles/{vin}/static-params — 准静态参数变更历史（分页）

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `page` / `size` | int | 1 / 20 | 分页 |

响应 `data`：`PageResult<VehicleStaticParam>`

| 字段 | 类型 | 说明 |
|---|---|---|
| `hwVersion` | String | 自动驾驶硬件版本 |
| `adVersion` | String | 自动驾驶软件版本 |
| `comType` | String | 蜂窝通讯类型 |
| `timeSync` | String | 时间同步方式 |
| `reportedAt` | long | 上报时间（毫秒时间戳） |

> 每次收到 `inh/up`（连接/重连时上报）都追加一条，因此该列表可直接用于
> 「车辆软件版本是否按要求升级」的审计。

### 6.4 事件（6 个）

#### 6.4.1 GET /api/v1/events/accidents — 事故列表（分页）

| 参数 | 类型 | 必需 | 默认 | 说明 |
|---|---|---|---|---|
| `vin` | String | 否 | — | 按车辆筛选 |
| `mediaStatus` | enum | 否 | — | `REPORTED` / `MEDIA_REQUESTED` / `MEDIA_ACCEPTED` / `MEDIA_RECEIVED` |
| `hours` | int | 否 | `168` | 时间窗（1 ~ 2160） |
| `page` / `size` | int | 否 | 1 / 20 | 分页 |

响应 `data`：`PageResult<AccidentEvent>`

| AccidentEvent 字段 | 类型 | 说明 |
|---|---|---|
| `eventId` | String | 事故 ID（车端生成） |
| `vin` | String | 车辆 VIN |
| `occurredAt` | long | 事故发生时间（毫秒时间戳） |
| `accidentDesc` | String | 事故描述 |
| `longitude` / `latitude` | Double | 事发位置 |
| `mediaStatus` | String | 取证状态（见 5.3） |
| `mediaRequestMsgId` | String | 平台下发媒体请求的 `msgId`（车端上传时作为 `ackMsgId`） |
| `mediaRequestedAt` | long | 媒体请求下发时间 |
| `mediaReceivedAt` | long | 视频归档完成时间 |

#### 6.4.2 GET /api/v1/events/accidents/{eventId} — 事故详情（含媒体）

响应 `data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `event` | AccidentEvent | 事故记录 |
| `media` | Array | 已归档的媒体资产列表 |
| `mediaComplete` | boolean | **取证要素是否齐备**：`media.size() >= 4`（上游 3.3 要求四方向） |
| `mediaByDirection` | Object | 按方向索引的归档情况，**未归档的方向为 `null`** |

> **`mediaByDirection` 显式标出缺失方向**，而不是「只列出已收到的文件」——
> 后者会让「只收到 2 个方向」看起来像「正常完成」，取证不完整却无人发现。

#### 6.4.3 GET /api/v1/events/accidents/pending — 待归档事故（催办清单）

| 参数 | 类型 | 默认 | 范围 | 说明 |
|---|---|---|---|---|
| `timeoutMinutes` | int | `10` | ≥1 | 距上报超过该分钟数仍未归档即纳入清单 |
| `limit` | int | `50` | 1 ~ 200 | 返回条数 |

响应 `data`：`AccidentEvent[]`，**按等待时长倒序**（等最久的排最前）。

> 该接口是运维主动干预的唯一入口：取证有实效要求，靠人工刷页面发现不了积压。

#### 6.4.4 GET /api/v1/events/faults — 故障列表（分页）

| 参数 | 类型 | 必需 | 默认 | 说明 |
|---|---|---|---|---|
| `vin` | String | 否 | — | 按车辆筛选 |
| `minSeverity` | int | 否 | — | **严重等级下限**（含） |
| `hours` | int | 否 | `168` | 时间窗（1 ~ 2160） |
| `page` / `size` | int | 否 | 1 / 20 | 分页 |

响应 `data`：`PageResult<FaultRecord>`

| FaultRecord 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 记录 ID |
| `vin` | String | 车辆 VIN |
| `reportedAt` | long | 上报时间（毫秒时间戳） |
| `errorNum` | Integer | 故障个数 |
| `itemCount` | Integer | 明细条数 |
| `maxSeverity` | Integer | 本批故障的最高等级 |
| `longitude` / `latitude` | Double | 上报位置 |

> ⚠️ **列表接口不返回 `items` 明细**（避免把懒加载集合带进列表序列化路径，N+1 查询）。
> 需要明细必须调用 6.4.5。

#### 6.4.5 GET /api/v1/events/faults/{id} — 故障详情（含明细）

响应 `data`：`{ "record": FaultRecord, "items": FaultItem[], "itemCount": int }`

| FaultItem 字段 | 类型 | 说明 |
|---|---|---|
| `errorCode` | String | 故障码 |
| `errorTimestamp` | long | 故障发生时间 |
| `errorSeverity` | String | 严重等级 `0~7` |
| `msgcount` | Integer | 累计上报次数（车端自计量） |
| `errorReason` | String | 故障原因 |
| `codeName` | String | **故障码中文名**（由附录 A 字典翻译） |
| `category` / `categoryLabel` | String | 故障分类与中文标签 |

#### 6.4.6 GET /api/v1/events/faults/top-codes — 故障码 Top 榜

| 参数 | 类型 | 默认 | 范围 | 说明 |
|---|---|---|---|---|
| `days` | int | `7` | 1 ~ 90 | 统计天数 |
| `limit` | int | `10` | 1 ~ 100 | 返回条数 |

响应 `data`：`[{ "errorCode", "codeName", "category", "categoryLabel", "count" }]`

### 6.5 运营调度（8 个）

#### 6.5.1 GET /api/v1/operations/tasks — 任务列表（分页）

| 参数 | 类型 | 必需 | 默认 | 说明 |
|---|---|---|---|---|
| `vin` | String | 否 | — | 按车辆筛选 |
| `status` | enum | 否 | — | `RUNNING` / `FINISHED` / `CANCELLED` |
| `page` / `size` | int | 否 | 1 / 20 | 分页 |

响应 `data`：`PageResult<TrackTask>`

| TrackTask 字段 | 类型 | 说明 |
|---|---|---|
| `taskId` | String | 任务 ID |
| `vin` | String | 车辆 VIN |
| `status` | String | 任务状态 |
| `startPosition` / `endPosition` | String | 起点 / 终点描述 |
| `startLongitude` / `startLatitude` | Double | 起点坐标 |
| `endLongitude` / `endLatitude` | Double | 终点坐标 |
| `startedAt` / `endedAt` | long | 开始 / 结束时间（毫秒时间戳） |
| `orphan` | boolean | ⚠️ **孤儿记录标记**：结束任务时 `taskId` 配不上对（见 3.5.2 ⑧） |

> **`orphan = true` 的处理**：这类记录说明企业侧任务下发逻辑存在缺陷（结束时报了新的 `taskId`）。
> 前端已将其**单独标红展示**，运营应据此排查上游调用方，而不是简单地清理数据。

#### 6.5.2 POST /api/v1/operations/tasks — 下发 / 结束任务

| 项 | 值 |
|---|---|
| 请求体 | `TaskCommandRequest` |
| 副作用 | **同时**上报监管平台（`task/up`）+ 本地落库；响应中的 `status` 即落库后的真实状态 |
| 幂等 | 否（重复调用会重复上报） |

| 字段 | 类型 | 必需 | 说明 |
|---|---|---|---|
| `vin` | String | **是** | 车辆 VIN（`@NotBlank`） |
| `action` | String | **是** | `1` 开始 / `2` 结束（`@NotBlank`） |
| `taskId` | String | 结束（`action=2`）时必填 | 任务 ID，必须与开始时一致 |
| `startPosition` | String | `action=1` 时必填 | 起点描述 |
| `startPoint` | Double[2] | `action=1` 时必填 | 起点坐标 **[纬度, 经度]** |
| `endPosition` | String | `action=1` 时必填 | 终点描述 |
| `endPoint` | Double[2] | `action=1` 时必填 | 终点坐标 **[纬度, 经度]** |

```json
{
  "vin": "LJGTE25J582000789",
  "action": "1",
  "taskId": "TASK_20251105_001",
  "startPosition": "科技园南门停车场",
  "startPoint": [22.3213, 130.4342],
  "endPosition": "中央商务区A座楼下",
  "endPoint": [22.3213, 130.4342]
}
```

**结束任务**：

```json
{ "vin": "LJGTE25J582000789", "action": "2", "taskId": "TASK_20251105_001" }
```

| 错误 | code | 说明 |
|---|---|---|
| `vin` 或 `action` 为空 | `1001` | `@NotBlank` 校验失败 |
| `action` 不是 `1`/`2` | `1001` | 参数取值非法 |
| 监管平台调用失败 | `5002` | 上游超时（30s）/ 重试 3 次后仍失败 |
| MQTT 通道未就绪 | `5001` | Broker 未连接 |

> ⚠️ **前端踩坑记录**：早期把 `action` 标为可选，漏传直接 `1001`；
> 且结束任务时未强制复用 `taskId`，产生 `ORPHAN_FINISH` 孤儿记录。
> 现已在前端表单层加校验（必填 + 结束后复用原 `taskId`），后端侧仅能事后标记为 `orphan`，
> **无法自动修复**——因为配对信息本身就没上报。这是本次联调暴露的最典型「契约不严导致数据脏」问题。

#### 6.5.3 GET /api/v1/operations/remote-driving — 远程驾驶记录（分页）

| 参数 | 类型 | 必需 | 默认 | 说明 |
|---|---|---|---|---|
| `vin` | String | 否 | — | 按车辆筛选 |
| `closed` | Boolean | 否 | `false` | `false` 查进行中；`true` 查已结束；不传查全部 |
| `page` / `size` | int | 否 | 1 / 20 | 分页 |

响应 `data`：`PageResult<RemoteDrivingRecord>`

| 字段 | 类型 | 说明 |
|---|---|---|
| `vin` | String | 车辆 VIN |
| `type` | String | `1` 发起接管 / `2` 结束接管 |
| `driver` | String | 驾驶员姓名 |
| `card` | String | 驾驶证号，**已打码**（如 `510123********1234`） |
| `remoteCockpitNo` | String | 远驾舱编号 |
| `occurredAt` | long | 事件时间（毫秒时间戳） |
| `closed` | boolean | 是否已结束 |
| `endedAt` | long | 结束时间，未结束为 `null` |
| `driverMissing` | boolean | ⚠️ **异常标记**：发起接管但缺驾驶员信息 |
| `durationMissing` | boolean | ⚠️ **异常标记**：已结束但无时长 |

> **两个异常标记的用途**：远驾接管记录是**责任认定依据**。
> 「发起接管但没记录驾驶员」意味着事故后无法追责到人——必须显性暴露而不是沉默接受。
> 这是列表接口主动计算并返回这两个布尔值的原因。
>
> ⚠️ **驾驶证号打码是强制的**：`card` 属个人敏感信息，管理接口一律打码；
> 明文仅在 MQTT 上行报文（`remote-driving/up`）中传输，且要求 TLS 1.2+。

#### 6.5.4 GET /api/v1/operations/vehicles/{vin}/cockpit — 当前接管舱位

响应 `data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `vin` | String | 车辆 VIN |
| `active` | boolean | 是否处于接管中 |
| `cockpitNo` | String | 舱位编号 |
| `driver` | String | 驾驶员姓名 |
| `startedAt` | long | 接管开始时间（毫秒时间戳） |

#### 6.5.5 POST /api/v1/operations/vehicles/{vin}/cockpit/start — 发起接管

| 参数 | 位置 | 必需 | 默认 | 说明 |
|---|---|---|---|---|
| `vin` | Path | 是 | — | 车辆 VIN |
| `starter` | Query | 否 | `2` | 发起人：`1` 交巡警 / `2` 监管平台 |

**副作用**：LRU 分配舱位 + 绑定车辆 + 上报监管平台（`remote-driving/up`，`type=1`）。

#### 6.5.6 POST /api/v1/operations/vehicles/{vin}/cockpit/end — 结束接管

无参数。响应 `data` 为 `Boolean`。副作用：上报 `remote-driving/up`（`type=2`）+ 关闭记录。

#### 6.5.7 GET /api/v1/operations/routes — 导航路径列表（分页）

| 参数 | 类型 | 必需 | 默认 | 说明 |
|---|---|---|---|---|
| `vin` | String | **是** | — | 车辆 VIN |
| `page` / `size` | int | 否 | 1 / 20 | 分页 |

响应 `data`：`PageResult<NavigationRoute>`

| 字段 | 类型 | 说明 |
|---|---|---|
| `vin` | String | 车辆 VIN |
| `pathPoints` | Array | 路径点集合，每点为 `[纬度, 经度]` |
| `pointCount` | Integer | 路径点数量 |
| `currentLongitude` / `currentLatitude` | Double | 上报时的当前位置 |
| `reportedAt` | long | 上报时间（毫秒时间戳） |

#### 6.5.8 GET /api/v1/operations/routes/latest — 最新导航路径

| 参数 | 类型 | 必需 | 说明 |
|---|---|---|---|
| `vin` | String | **是** | 车辆 VIN |

响应 `data`：单个 `NavigationRoute`；无记录时 `3001`。

### 6.6 地图阻断（5 个）

> 数据有两个来源（`MapBarrier.Source`）：`PUSH`（MQTT `mapehn/down` 推送）与 `PULL`（HTTP 主动拉取，见 7.3）。

#### 6.6.1 GET /api/v1/map-barriers — 今日阻断设施（全量）

无参数。返回**今日**全部阻断设施（命中后端当日缓存）。

响应 `data`：`BarrierView[]`

| BarrierView 字段 | 类型 | 说明 |
|---|---|---|
| `barrierId` | String | 阻断设施编号 |
| `barrierName` | String | 名称 |
| `barrierType` | String | `20` 禁行区域 / `21` 限速区域 |
| `barrierTypeLabel` | String | 类型中文名 |
| `areaCode` | String | 区域编码 |
| `barrierPolygon` | Double[][] | 多边形顶点，每点 `[纬度, 经度]` |
| `pointCount` | Integer | 顶点数（`1` 表示单点阻断） |
| `dateType` / `dateTypeLabel` | String | 时间段类型与中文名 |
| `timeRange` | String[][] | 时间段集合 |
| `speedLimit` | Double | 限速值（`barrierType=21` 时有意义） |
| `source` | String | `PUSH` / `PULL` |
| `effectiveDate` | String | 生效日期（`yyyy-MM-dd`） |
| `receivedAt` | long | 接收时间（毫秒时间戳） |

#### 6.6.2 GET /api/v1/map-barriers/page — 阻断设施（分页，全量）

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `page` / `size` | int | 1 / 50 | 分页 |

#### 6.6.3 GET /api/v1/map-barriers/by-date — 按日期回看

| 参数 | 类型 | 必需 | 格式 | 说明 |
|---|---|---|---|---|
| `date` | String | **是** | `yyyy-MM-dd` | 查询日期 |

响应 `data`：`BarrierView[]`（指定日期的全量设施，用于事故复盘）

#### 6.6.4 GET /api/v1/map-barriers/summary — 阻断概览

响应 `data`：按类型统计的汇总（总数 / 禁行数 / 限速数 / 各区域分布）

#### 6.6.5 POST /api/v1/map-barriers/pull — **立即同步**（手动触发拉取）

| 参数 | 类型 | 必需 | 说明 |
|---|---|---|---|
| `areaCode` | String | 否 | 区域编码；不传使用默认区域 |

**副作用**：调用上游 `POST /manager/api/v1/event/mapehn`（见 7.3）拉取今日全量并落库。

| 错误 | code | 说明 |
|---|---|---|
| 上游不可达 / 超时 | `5002` | 监管平台调用失败 |

> **幂等性说明**：该接口**是幂等的**。落库时以 `barrierId` 为业务唯一键做 upsert，
> 重复拉取不会产生重复记录，只会刷新字段值。
> 因此运营可以放心在「怀疑数据不同步」时点一次「立即同步」。
>
> 除手动触发外，平台还会**每天 00:05 自动执行一次**（`scheduledPull`），
> 因为上游 `mapehn` 存在「推送丢失且无补偿」的固有风险（MQTT QoS 1 只能保证不丢链路消息，
> 不能保证平台在线）。

### 6.7 字典（6 个）

> **为什么字典要走接口下发**：故障码、摄像头方向、报文类型这些枚举**同时**被「协议解析」与「前端展示」使用。
> 若前后端各维护一份，协议升版时必然出现显示不一致，且这类问题排查成本极高。
> 统一由后端下发，前端只渲染。

| # | Path | 说明 | 返回项 |
|---:|---|---|---|
| 1 | `GET /api/v1/dict/fault-codes` | 故障码全量字典（附录 A.1） | `[{code, name, category, categoryLabel, description}]` |
| 2 | `GET /api/v1/dict/fault-categories` | 故障分类字典（5 大类） | `[{value, label}]` |
| 3 | `GET /api/v1/dict/camera-directions` | 摄像头方向 | `[{value:"1", label:"前向"}, …]` 共 4 项 |
| 4 | `GET /api/v1/dict/mqtt-types` | MQTT 报文类型（18 类） | `[{code, description, scope, naturalDirection, qos, docRef, needAck, ackTypeCode}]` |
| 5 | `GET /api/v1/dict/error-codes` | 平台错误码 | `[{code, message}]` |
| 6 | `GET /api/v1/dict/stream-types` | 视频流类型 | `[{value:"1", label:"m3u8"}, {value:"2", label:"flv"}]` |

> **`dict/mqtt-types` 的 `docRef` 字段**：直接给出上游文档章节号（如 `5.1.4.2.1`），
> 排障时可一键对照原文，省去「这个字段到底哪来的」的翻文档时间。

### 6.8 链路监控（6 个）

#### 6.8.1 GET /api/v1/monitor/health-check — 健康检查

| 项 | 值 |
|---|---|
| 鉴权 | **白名单**（运维探测用，无需令牌） |
| 参数 | 无 |

响应 `data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `status` | String | 总体状态，如 `UP` |
| `components` | Object | 各组件明细（数据源、MQTT、缓存、存储等） |
| `checks` | Array | 检查项列表（名称 / 状态 / 详情） |
| `timestamp` | long | 检查时间 |

#### 6.8.2 GET /api/v1/monitor/mqtt — MQTT 连接与通道状态

响应 `data`（`MqttMetrics`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `connected` | boolean | Broker 是否已连接 |
| `brokerUrl` | String | 当前 Broker 地址 |
| `clientId` | String | 当前 clientId（`e_{enterpriseId}`） |
| `enterpriseId` | String | 企业 ID |
| `state` | String | 连接状态机状态 |
| `connectedAt` | long | 连接建立时间，未连接为 `null` |
| `reconnectCount` | int | 累计重连次数 |
| `subscriptions` | String[] | 当前订阅的 Topic 过滤器列表 |
| `inboundQueueSize` | int | 入站队列当前长度 |
| `inboundQueueCapacity` | int | 入站队列容量 |
| `inboundDropped` | long | 入站队列累计丢弃数 |
| `offlineQueueSize` | int | 离线队列当前长度 |
| `offlineQueueCapacity` | int | 离线队列容量 |
| `offlineDropped` | long | 离线队列累计丢弃数（按优先级丢弃） |
| `pendingAckCount` | int | 待确认（重发中）报文数 |
| `consumerThreads` | int | 消费线程数（默认 4） |
| `virtualThreads` | boolean | 是否使用虚拟线程 |

> ⚠️ **`inboundDropped > 0` 是必须立刻处理的告警信号**：入站队列满说明消费能力不足，
> 丢弃意味着**报文永久丢失**（监管取证数据缺失）。此时的处置顺序是：
> 先扩容消费线程（`dssad.mqtt.consumer-threads`），再排查是否有单条报文处理耗时过长。

#### 6.8.3 GET /api/v1/monitor/mqtt/stats — 报文统计

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `windowMinutes` | int | `5` | 统计时间窗（分钟） |

响应 `data`（`MqttAuditStats`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `windowMinutes` | int | 统计窗口 |
| `total` | long | 总报文数 |
| `success` / `failed` | long | 成功 / 失败数 |
| `byType` | Object | 按 `mqttType` 计数，如 `{"low-base": 3200, "accident": 2}` |
| `byStatus` | Object | 按处理状态计数（`OK` / `INVALID` / `UNSUPPORTED` / `DUPLICATE` / `ERROR`） |
| `byDirection` | Object | 按方向计数（`UP` / `DOWN`） |
| `avgLatencyMs` | double | 平均处理耗时（毫秒） |
| `maxLatencyMs` | long | 最大处理耗时（毫秒） |

#### 6.8.4 GET /api/v1/monitor/messages — 报文留痕检索（分页）

| 参数 | 类型 | 必需 | 默认 | 说明 |
|---|---|---|---|---|
| `senderId` | String | 否 | — | 发送方标识（VIN 或企业 ID） |
| `mqttType` | String | 否 | — | 报文类型 |
| `processStatus` | String | 否 | — | `OK` / `INVALID` / `UNSUPPORTED` / `DUPLICATE` / `ERROR` |
| `page` / `size` | int | 否 | 1 / 20 | 分页 |

响应 `data`：`PageResult<MqttMessageLog>`

| MqttMessageLog 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 记录 ID |
| `senderId` | String | 发送方标识 |
| `topic` | String | 原始 Topic |
| `mqttType` | String | 报文类型 |
| `direction` | String | `UP` / `DOWN` |
| `msgId` | String | 报文消息编号 |
| `rawPayload` | String | **原始报文全文**（问题回溯的关键证据） |
| `processStatus` | String | 处理状态 |
| `ackCode` | String | 回复的 `res` 值 |
| `errorMessage` | String | 失败原因 |
| `latencyMs` | Integer | 处理耗时 |
| `clockSkewMs` | Long | **对端与平台的时钟偏差**（毫秒） |
| `receivedAt` | long | 接收时间（毫秒时间戳） |

> **`clockSkewMs` 的用途**：车端时间不准会导致轨迹点时序错乱、事故时间与视频时间对不上。
> 该字段让「时钟漂移」这个隐蔽问题变成可查询的指标，而不是等到取证时才发现视频时间对不上。

#### 6.8.5 GET /api/v1/monitor/messages/trace/{msgId} — 按 `msgId` 全链路追溯

| 参数 | 类型 | 位置 | 说明 |
|---|---|---|---|
| `msgId` | String | Path | 报文消息编号 |

响应 `data`：`MqttMessageLog[]`，**该 `msgId` 在全部通道上的全部记录按时间升序**。

> 用途：一个 `msgId` 可能同时出现在「车端上报」「平台 ACK」「重复上报」等多条记录中。
> 该接口把分散的留痕拼成一条时间线，用于回答「这条报文到底发生了什么」。

#### 6.8.6 GET /api/v1/monitor/cache — 缓存与限流指标

响应 `data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `cacheType` | String | 缓存形态，如 `two-level-caffeine-redis` / `two-level-caffeine` |
| `l1` | Object | L1（Caffeine）配置：`maxSize` / `ttlSeconds` / `hitCount` / `missCount` |
| `l2` | Object | L2（Redis）状态：`enabled` / `ttlSeconds` / `hitCount` / `missCount` |
| `hitRate` | double | 综合命中率 |
| `caches` | Array | 各缓存名独立指标（`name` / `l1Hits` / `l2Hits` / `misses` / `hitRate`） |
| `rateLimitAlgorithm` | String | 限流算法标识，固定为 `sliding-window-counter` |
| `rateLimitHttpPerMinute` | int | 单车 HTTP 每分钟上限 |
| `rateLimitMqttPerSecond` | int | 单车 MQTT 每秒上限 |

> **`cacheType` 为什么重要**：`dssad.cache.enabled=false` 或 Redis 未启用时，
> 缓存会**静默退化**为单机甚至直接不缓存（NoOp）。该字段让「二级缓存到底生效没有」
> 变成可验证的事实，而不是靠猜。
>
> 命中率指标同时覆盖 L1 与 L2 命中：
> **L1 命中率高、L2 命中数持续增长** = 多实例部署下缓存隔离在正常工作；
> **L2 命中数长期为 0** = L1 TTL 过长或实例数不足，二级缓存未发挥作用。

### 6.9 模拟器（3 个，**仅本地/联调启用**）

> ⚠️ **安全提示**：该组接口在 `WebConfig` 中**被列入鉴权白名单**（无需 `X-Token`），
> 目的是让自动化脚本与联调工具能直接构造场景。
> **生产环境必须关闭**（`dssad.simulator.enabled=false`），否则任何人都能伪造事故与故障数据污染证据链。

| # | Method | Path | 说明 |
|---:|---|---|---|
| 1 | POST | `/api/v1/simulator/mqtt?topic=...` | 注入任意 MQTT 报文，body 为原始 JSON 字符串 |
| 2 | POST | `/api/v1/simulator/scenario/accident?vin=...&vehicleType=...` | 一键构造事故场景（含后续媒体请求联动） |
| 3 | POST | `/api/v1/simulator/scenario/fault?vin=...&vehicleType=...` | 一键构造故障场景 |

**参数**

| 接口 | 参数 | 必需 | 默认 | 说明 |
|---|---|---|---|---|
| `/simulator/mqtt` | `topic` | 是 | — | 目标 Topic（Query） |
| | body | 是 | — | 原始报文字符串（`@RequestBody String`，**不是对象**） |
| `/simulator/scenario/accident` | `vin` | 是 | — | 车辆 VIN |
| | `vehicleType` | 否 | `low_speed` | 车型 |
| `/simulator/scenario/fault` | `vin` | 是 | — | 车辆 VIN |
| | `vehicleType` | 否 | `low_speed` | 车型 |

**为什么 `vin` 是普通字符串而不是 `@PathVariable`**：模拟器要能对**未注册的 VIN** 构造数据
（用于验证「未知车辆上报」的容错路径），设为 Path 会诱导实现方先查库校验存在性。

---

## 7. 本平台作为客户端调用的上游接口

> 本节描述**反方向**的调用：本平台主动调用监管平台的 HTTP 接口。
> 上游章节位于《数据接口对接文档》第 5.2 / 6.2 节。

### 7.1 调用约定

| 项 | 约定 | 配置项 |
|---|---|---|
| Base URL | 监管平台域名 | `dssad.regulatory.base-url` |
| 超时 | **30 秒** | `dssad.regulatory.timeout-millis = 30000` |
| 重试次数 | **3 次** | 见下 |
| 重试间隔 | **1s / 2s / 4s**（递增） | `dssad.regulatory.retry-delays-millis = [1000,2000,4000]` |
| 密钥盐 | `CMAT`（`secret = SHA256(clientId + salt)`） | `dssad.regulatory.secret-salt = CMAT` |
| 失败处理 | 重试耗尽后返回 `5002`「监管平台调用失败」 | — |

> **与上游 8.3 的一致性**：上游规定「超时 30s、重试 3 次、间隔 1s/2s/4s」，
> 本平台配置默认值与之一致，可直接使用。

### 7.2 POST /passport/api/v1/auth/access-enterprise-mqtt — 换取 MQTT 连接密钥

| 项 | 值 |
|---|---|
| 上游章节 | 6.2.2.1.1 |
| 调用时机 | 启动连接 Broker 前；密码轮换时 |
| 本平台配置 | `dssad.regulatory.mqtt-access-path` |

**请求体**

```json
{
  "clientId": "DSSAD-ENT-0001",
  "secret": "42ee62a8f867b995d4fe851d5d188ab42b5a4aa4cb1c70a45347e149731361b4"
}
```

| 字段 | 类型 | 必需 | 说明 |
|---|---|---|---|
| `clientId` | String | 是 | 企业 ID |
| `secret` | String | 是 | 密钥，`SHA256(clientId + "CMAT")` |

**响应 `data`**

| 字段 | 类型 | 说明 |
|---|---|---|
| `token` | String | **连接 MQTT 的密码** |
| `expiredAt` | Long | 过期时间戳 |

> **本平台行为**：当 `dssad.mqtt.password` 已显式配置时**不会调用本接口**（优先使用配置值，
> 便于离线环境与自动化测试）；留空时自动换取。

### 7.3 POST /manager/api/v1/event/mapehn — 今日地图增强信息（主动拉取）

| 项 | 值 |
|---|---|
| 上游章节 | 6.2.2.2.1 |
| 调用时机 | **每天 00:05 定时**；以及 `POST /api/v1/map-barriers/pull` 手动触发 |
| 本平台配置 | `dssad.regulatory.mapehn-path` |

**请求体**：`{ "enterpriseId": "xxxxxxxx", "areaCode": "xxxxx" }`

**响应 `data`**

| 字段 | 类型 | 说明 |
|---|---|---|
| `areaCode` | String | 区域编码 |
| `barriers` | Object[N] | 今日交通阻断设施列表（字段同 3.6.3 的 `Mapehn`） |

> **为什么 MQTT 推送之外还需要 HTTP 拉取**：
> MQTT 的 QoS 1 只保证「链路建立后消息不丢」，**不保证平台在线期间的所有推送都能收到**
> （平台重启、网络中断超过 Broker 会话保留期都会漏）。
> 每日一次全量拉取是**兜底补偿**，确保「今日阻断设施」最终一致。
> 两条路径落库时以 `barrierId` 做 upsert，通过 `source` 字段区分来源（`PUSH` / `PULL`）。

---

## 8. 附录

### 8.1 附录 A：故障码字典（上游附录 A.1 全量）

**共 29 个故障码，分 5 大类。**

#### 硬件故障（6）

| 故障码 | 三级故障 | 故障描述 |
|---|---|---|
| `4352` | 电池系统故障 | 车端电池系统故障（如电芯损坏、电压异常） |
| `4353` | 电机系统故障 | 车端电机系统故障（如电机转速异常、扭矩失效） |
| `4355` | 制动液液位低 | 车端制动液液位低于安全阈值 |
| `4356` | 蓄电池电压低 | 车端低压蓄电池电压异常（如亏电） |
| `4357` | 胎压报警 | 车端轮胎压力异常（过高 / 过低） |
| `4358` | 动力电池故障报警 | 电动车动力电池故障（如热失控、电芯不均衡） |

#### 基础软件故障（5）

| 故障码 | 三级故障 | 故障描述 |
|---|---|---|
| `4609` | 决策功能异常 | 智能驾驶决策模块（如行为决策）输出异常 |
| `4610` | 规划功能异常 | 路径规划模块（如局部轨迹规划）生成失败 |
| `4611` | 纵向控制异常 | 纵向控制（如 ACC/AEB）执行失效 |
| `4612` | 横向控制异常 | 横向控制（如 LKA/LCC）执行失效 |
| `4613` | GNSS 授时故障 | GNSS 时间同步异常（如授时信号丢失） |

#### 系统故障（9）

| 故障码 | 三级故障 | 故障描述 |
|---|---|---|
| `4864` | GNSS 信号故障 | GNSS 定位信号丢失或精度异常（如无卫星信号） |
| `4865` | CAN 总线接口断开 | CAN 总线与 ECU / 传感器连接断开 |
| `4866` | 车载以太网故障 | 车载以太网（如 ADAS 域控制器交互）通信异常 |
| `4867` | 传感器数据异常 | 传感器（摄像头 / 雷达）输出数据无效 |
| `4868` | 自动驾驶计算平台异常 | 自动驾驶域控制器（如 Orin）故障 |
| `4870` | 车内网心跳异常 | SOME/IP、DDS 等以太网通信协议心跳检测失败或超时，ECU 间服务连接异常中断或未恢复 |
| `4992` | 动力控制系统故障 | 动力控制系统故障 |
| `4993` | 底盘控制系统故障 | 底盘控制系统故障 |
| `4994` | 车内时间同步异常 | PTP / NTP 内网同步异常 |

#### 业务服务故障（7）

| 故障码 | 三级故障 | 故障描述 |
|---|---|---|
| `5121` | 远程控车指令执行失败 | 接收云端远程控车指令后无法执行或执行超时 |
| `5122` | 协同控制指令执行失败 | 接收云端协同控制指令后无法执行或执行超时 |
| `5123` | 协同决策指令执行失败 | 接收云端协同决策指令后无法执行或执行超时 |
| `5124` | 云端任务执行失败 | 接受云端的任务，执行失败 |
| `5125` | 协同感知融合失败 | 无法有效融合路侧或云端提供的感知数据（如遮挡车辆补全失败） |
| `5126` | 高精地图匹配失败 | 车辆定位结果无法与高精地图车道拓扑对齐 |
| `5136` | 变道决策冲突 | 多目标博弈下变道策略矛盾，导致长时间犹豫或急刹 |

#### 运维服务故障（2）

| 故障码 | 三级故障 | 故障描述 |
|---|---|---|
| `5380` | OTA 升级状态未知 | OTA 任务状态未上报或处于卡死状态（如下载完成但未安装） |
| `5381` | 黑匣子（EDR/DSSAD）存储异常 | 自动驾驶事件数据记录器写入失败或存储介质损坏 |

> **未识别的故障码**：字典中不存在的 `errorCode` 会被归入 `UNKNOWN` 分类，
> 并**如实展示原始码值 + 标注「未知故障码」**，而不是丢弃或显示为空——
> 未知码本身就是「车端字典与平台不同步」的信号，需要被发现。
>
> ⚠️ 上游附录 A.1 的表格在 PDF 中跨页断行，`4993`「底盘控制系统故障」的故障描述被截断为
> 「底盘控制系统故障」与「障」两行；本表已按语义合并。登记为 D-06。

### 8.2 附录 B：枚举字典汇总

| 字段 | 取值 |
|---|---|
| `comType` 蜂窝通讯类型 | `0` 未知 / `1` 4G / `2` 5G / `3` 5GA / `4` 6G（缺省 `0`） |
| `timeSync` 时间同步方式 | `0` 无法获取 / `1` 不支持 / `2` NTP / `3` GNSS / `4` GNSS+NTP / `5` 其他（缺省 `5`） |
| `gnssStatus` 定位状态 | `0` 缺省 GNSS 无效 / `1` 单点定位 / `2` 差分 / `3` 无效 PPS / `4` 固定解 / `5` 浮点解 / `6` 正在估算 / `7` 人工输入固定值 / `8` 模拟模式 / `9` WAAS 差分 / `10` GNSS+IMU / `11` DGPS+IMU / `12` RTK+IMU / `13` RTK+IMU+视觉 |
| `chargeState` 充电状态 | `0` 缺省 / `1` 未充电 / `3` 正在充电 / `4` 充电故障 |
| `drivingMode` 驾驶模式 | `0` 自动驾驶 / `1` 人工驾驶 / `2` 远程接管 |
| `errorSeverity` 严重等级 | `0~1` 无影响 / `2~3` 影响功能可恢复 / `4~5` 需人工处理 / `6~7` 严重或不可恢复 |
| `res` 消息接收标志 | `1` 确认 / `2` 消息有误 / `3` 不支持 |
| `cameraDirection` 摄像头方向 | `1` 前向 / `2` 右向 / `3` 后向 / `4` 左向 |
| `barrierType` 阻断类型 | `20` 禁行区域 / `21` 限速区域 |
| `dateType` 时间段类型 | `1` 工作日 / `2` 节假日 / `3` 每日 / `4` 周六周日 |
| `type` 视频流类型（实时视频，上游 6.3.2.1.1） | `1` m3u8 / `2` flv |
| `type` 视频文件类型（历史视频，上游 6.3.2.1.2） | `1` mp4 ⚠️ |
| `type` 远驾接管类型 | `1` 发起接管 / `2` 结束接管 |
| `taskAction` 任务动作 | `1` 开始 / `2` 结束 |
| `starter` 发起人 | `1` 交巡警 / `2` 监管平台 |
| `vehicleType` 车型 | `low_speed` 功能性无人车 / `high_speed` 智能网联车 |

> ⚠️ **`type` 字段的同名异义陷阱**：实时视频接口中 `type` 的 `1` 表示 **m3u8**，
> 而历史视频接口中 `type` 的 `1` 表示 **mp4**。两个接口的 `type` 枚举**不通用**，
> 实现时不能把枚举常量复用（否则会把历史文件的类型显示成 m3u8）。登记为 D-05。

### 8.3 附录 C：文档缺陷与澄清清单

以下问题均来自**上游《数据接口对接文档》V1.0.0 原文**，或本次实现中发现的**内部不一致**。
每条都给出本平台的兼容策略，**不阻塞对接**，但建议向上游澄清后统一。

#### 上游文档缺陷（D 系列）

| 编号 | 位置 | 问题 | 本平台兼容策略 | 影响 |
|---|---|---|---|---|
| **D-01** | 6.1.4.2.3 / 6.1.5.1.3 / 附录 B.2 | **`groute` 与 `groute-resp` 作用域不对称**：请求是云云 3 段（`uvodp/{enterpriseId}/groute/up`），回复却是车云 5 段（`uvodp/{vehicleType}/{vin}/groute-resp/down`）。请求 Topic 中没有车辆身份，**单靠镜像无法构造回复 Topic** | 当回复类型作用域与请求不同时，回退使用**报文体内携带的 `vin`** 定位车辆；`vehicleType` 缺失时取默认值 `low_speed` | 最坏情况是 `high_speed` 车型收不到 ACK，按其重发策略重试若干次后放弃。若上游确认应为云云作用域，只需把 `MqttType.GROUTE_RESP` 的作用域改回 `ENTERPRISE`，构造器无需改动 |
| **D-02** | 5.1.5.2.1 | **`accident-media/down` 字段名矛盾**：字段表写 `cameraDirection`，同节数据示例写 `sensorDirection` | **两个字段都接收**（优先 `cameraDirection`，为空回落 `sensorDirection`）；**发送统一用 `cameraDirection`** | 接收侧零风险；发送侧若上游只认 `sensorDirection`，需一次配置级切换 |
| **D-03** | 6.1.4.2.3 | `groute/up` **字段表有 `msgId`，但数据示例没有** | 按**字段表**要求 `msgId`；缺失时回 `res=2` | 若上游实际不发 `msgId`，会持续收到 `res=2`。**建议优先澄清此条** |
| **D-04** | 8.1 | **重发次数自相矛盾**：「间隔 3 秒**至少**发送 5 次」与「已重发次数**满 3 次**时停止重发」在同一段中冲突 | 取**更保守的并集**：未确认走「5s / 3 次」（5.1.4.1），明确异常走「3s / 5 次」（8.1），两者并存可配 | 无功能影响；重发次数偏多会增加带宽消耗 |
| **D-05** | 6.3.2.1.1 vs 6.3.2.1.2 | **`type` 枚举同名异义**：实时视频 `1`=m3u8，历史视频 `1`=mp4 | 两处**分别定义枚举常量**，不复用 | 复用会导致历史文件类型显示错误 |
| **D-06** | 附录 A.1 | 表格跨页断行导致 `4993`「底盘控制系统故障」的描述被截断（拆成「底盘控制系统故障」+「障」） | 按语义合并为「底盘控制系统故障」 | 无 |
| **D-07** | 5.2.2.2.1 | 要求鉴权头 `X-Token`（**监管平台**侧令牌），但企业云端形态下企业侧不持有该令牌 | 改用**更强的业务态鉴权**：`ackMsgId` 必须与平台已下发的媒体请求编号一致；且 `multipart` 二进制体无法做 HMAC 签名 | 无（安全性不降反升）。若上游强制要求 `X-Token`，需上游为企业侧签发可验证的令牌 |
| **D-08** | 7.1 | **错误码只定义了 `0000 成功`**，全部失败场景无码可依 | 自行扩展 9 个区间（见 2.3），`0000` 语义与上游完全一致，扩展码不与上游冲突 | 对端若严格校验错误码白名单需同步扩展 |
| **D-09** | 6.3 | **未定义「结束远程驾驶接管」接口** | 企业侧补充 `POST /enterprise/api/v1/event/remote-cockpit-end`（见 4.5） | 无该接口则监管侧看到的接管状态永远「进行中」 |
| **D-10** | 5.1.3 / 5.1.4.4.1 / 5.1.4.4.2 / 6.1.4.2.1 / 6.1.4.2.2 / 6.1.5.2.1 | 多处「流程」为**图片**，PDF 文本层无内容，无法据此实现时序细节 | 依据字段表与数据示例推断，并在本文档中补全显式时序（见 3.4.3、5.2） | 时序细节可能与上游预期存在偏差，建议索取图片原文 |
| **D-11** | 3.3 / 5.1.4.1.1 / 5.1.5.2.1 | **事件视频时长前后不一致**：3.3 节要求「前 **15** 秒 + 后 5 秒」；5.1.5.2.1 定义亦为「前 **15** 秒 + 后 5 秒」；但 **5.1.4.1.1 定义写「事故时间点前后 10 秒」** | 按 **3.3 节（最严）的「前 15 秒 + 后 5 秒」** 要求车端；平台侧不校验时长，只校验四方向齐备 | 若车端按 10 秒实现，视频可能不足以覆盖事发前后完整过程 |

#### 本项目内部不一致（I 系列，待修正）

| 编号 | 位置 | 问题 | 处置 |
|---|---|---|---|
| **I-01** | `DictController.mqttTypes()` 注释 | 注释写「MQTT 报文类型字典（**16 种**）」，实际枚举为 **18 种** | 待修正注释（仅注释错误，接口返回正确） |
| **I-02** | `DashboardController.trend()` 注释 | 注释写「近 N 天趋势（事故数、故障数、**任务数、在线车辆数**）」，实际只返回 `accident` 与 `fault` | 二选一：补实现或改注释。当前以本文档 6.2.2 节为准 |
| **I-03** | `DashboardService` 的 `byType` 等键名 | `vehicleByType` 的键为车型枚举字符串，前端需容错处理未知车型 | 前端已容错；建议后端在字典中补车型标签 |

### 8.4 附录 D：变更记录

| 版本 | 日期 | 变更内容 | 作者 |
|---|---|---|---|
| V1.0.0 | 2026-09-22 | 首版冻结：MQTT 18 类报文、企业云端 4+1 个 HTTP 接口、平台管理 47 个 REST 接口、上游客户端 3 个接口、错误码字典、故障码字典（29 项）、11 条上游缺陷 + 3 条内部不一致 | DSSAD 云平台 |

---

**文档结束**

> 本文档由代码实现反向核对生成（逐接口对照 Controller 映射、DTO 字段、实体定义、配置项），
> 与代码不一致时**以代码为准**，并请同步更新本文档与 8.3 节的清单。

