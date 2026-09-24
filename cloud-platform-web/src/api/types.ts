/**
 * 平台接口的 TypeScript 类型定义。
 *
 * 字段名**逐个对照后端实体/记录类**（`com.genvict.dssad.cloud.domain.entity.*`
 * 与 `web/dto`），不是我凭接口文档猜的 —— 前端类型与后端契约不一致时，
 * TypeScript 不会报错，只会在运行时显示 `undefined`，属于「静默失效」。
 *
 * 时间字段统一为 `number | number[] | string`：后端把时间序列化成 13 位毫秒
 * 或 `[年,月,日]` 数组，展示一律走 `@/utils/format`。
 */

/** 后端时间字段的联合类型，见 utils/format.ts 顶部说明。 */
export type TimeValue = number | number[] | string | null

/** 所有实体的公共审计字段（BaseEntity）。 */
export interface BaseEntity {
  createdAt?: TimeValue
  updatedAt?: TimeValue
}

/** 分页结果（PageResult<T>：list / total / page / size）。 */
export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}

// ==================== 车辆 ====================

export interface Vehicle extends BaseEntity {
  vin: string
  enterpriseId?: string
  /** 车辆类型（字典 value，见 /api/v1/dict/mqtt-types 之外的车型约定） */
  vehicleType?: string
  plateNo?: string
  model?: string
  deviceSn?: string
  hwVersion?: string
  adVersion?: string
  comType?: string
  timeSync?: string
  online: boolean
  lastOnlineAt?: TimeValue
  lastInhAt?: TimeValue
  lastStateAt?: TimeValue
  lastBaseAt?: TimeValue
  accidentCount?: number
  faultCount?: number
  version?: number
}

/**
 * 车辆详情接口返回的复合结构（`VehicleController.detail`）。
 *
 * 后端把车辆档案**嵌在 `vehicle` 下**（`{vehicle, latestState, position}`），
 * 不是把字段平铺在顶层 —— 这一点如果搞错，页面不会报错，
 * 只会把所有字段显示成 `—`、在线状态显示成「离线」，属于最难发现的一类对接错误。
 */
export interface VehicleDetail {
  vehicle: Vehicle
  latestState: VehicleState | null
  position: VehiclePosition | null
}

/** 车辆实时位置（TelemetryService.Position）。 */
export interface VehiclePosition {
  ts: TimeValue
  longitude: number | null
  latitude: number | null
  speed: number | null
  heading: number | null
  /** 0 自动驾驶 / 1 人工 / 2 远程接管 */
  drivingMode: number | null
}

/**
 * 车辆运行状态。
 *
 * 有两条来源：
 *   - `/vehicles/{vin}/state` 读缓存/回源，字段来自 `CarUpMessages.LowState`；
 *   - `/vehicles/{vin}/state-history` 读快照表，字段来自 `VehicleStateSnapshot`。
 * 两者共有字段在此声明，差异字段标可选。
 */
export interface VehicleState {
  vin: string
  msgId?: string
  timestamp?: TimeValue
  reportedAt?: TimeValue
  gnssStatus?: string
  vehicleTotalWeight?: number | null
  tirePressure?: number[] | string | null
  endurance?: number | null
  mileage?: number | null
  soc?: number | null
  batteryCapacity?: number | null
  /** 协议原始值（带 +100 偏移量，DB 保留原值便于对账） */
  temperature?: number | null
  /** 真值摄氏度（后端派生字段） */
  temperatureCelsius?: number | null
  chargeState?: string | null
}

export interface VehicleTrackPoint {
  id?: number
  vin: string
  ts: TimeValue
  longitude: number | null
  latitude: number | null
  altitude?: number | null
  speed?: number | null
  accSpeed?: number | null
  drivingMode?: number | null
  heading?: number | null
}

export interface VehicleStaticParam {
  id?: number
  vin: string
  msgId?: string
  reportedAt?: TimeValue
  hwVersion?: string
  adVersion?: string
  comType?: string
  timeSync?: string
}

// ==================== 事件（事故 / 故障） ====================

/** 事故取证状态机（AccidentEvent.MediaStatus）。 */
export type AccidentMediaStatus =
  | 'REPORTED'
  | 'MEDIA_REQUESTED'
  | 'MEDIA_ACCEPTED'
  | 'MEDIA_ARCHIVED'
  | 'MEDIA_FAILED'
  | string

export interface AccidentEvent extends BaseEntity {
  id: number
  eventId: string
  vin: string
  enterpriseId?: string
  msgId?: string
  occurredAt?: TimeValue
  accidentDesc?: string
  latitude?: number | null
  longitude?: number | null
  mediaStatus?: AccidentMediaStatus
  mediaRequestedAt?: TimeValue
  mediaCount?: number
  mediaExpectedCount?: number
  mediaRequestMsgId?: string
}

export interface FaultItem {
  id?: number
  vin: string
  errorCode: string
  errorTimestamp?: TimeValue
  errorSeverity?: string
  msgCount?: number
  errorReason?: string
}

export interface FaultRecord extends BaseEntity {
  id: number
  vin: string
  msgId?: string
  reportedAt?: TimeValue
  errorNum?: number
  /** 1~10，≥6 视为严重故障（与「故障趋势/严重故障数」口径一致） */
  maxSeverity?: number | null
  latitude?: number | null
  longitude?: number | null
  items?: FaultItem[]
}

/** 故障明细接口返回的复合结构（EventController.faultDetail）。 */
export interface FaultDetail {
  record: FaultRecord
  items: FaultItem[]
  itemCount: number
}

/**
 * 事故详情接口返回的复合结构（EventController.accidentDetail）。
 *
 * `mediaComplete` 由后端按「文档 3.3 要求四方向视频」判定（media.size() >= 4），
 * 前端不要自己用 `media.length` 重算 —— 取证要素的门槛属于监管口径，只能有一处定义。
 */
export interface AccidentDetail {
  event: AccidentEvent
  media: MediaAsset[]
  mediaComplete: boolean
}

/** 故障码字典项（/api/v1/dict/fault-codes）。 */
export interface FaultCodeEntry {
  code: string
  name?: string
  category?: string
  categoryLabel?: string
  description?: string
}

/** 通用字典项：不同字典用 value 或 code 作为键，标签统一叫 label。 */
export interface DictEntry {
  value?: string
  code?: string
  label: string
}

/** 平台错误码字典项（/api/v1/dict/error-codes）。 */
export interface ErrorCodeEntry {
  code: string
  message: string
}

/**
 * MQTT 报文类型字典项（/api/v1/dict/mqtt-types）。
 *
 * 比通用 `DictEntry` 信息量大得多：`docRef` 是接口文档章节号，
 * `needAck` / `ackTypeCode` 描述应答关系。排障时「这个 Topic 该不该有回复」
 * 直接查这里，不需要翻文档。
 */
export interface MqttTypeEntry {
  /** 报文类型字面量，如 accident / error-report / low-state */
  code: string
  description?: string
  /** 归属：ENTERPRISE / CAR */
  scope?: string
  /** 协议自然方向：UP / DOWN */
  naturalDirection?: string
  qos?: number
  /** 接口文档章节号，如 "5.1.1.1" */
  docRef?: string
  needAck?: boolean
  ackTypeCode?: string
}

// ==================== 运营 ====================

/**
 * 任务状态（`TrackTask.TaskStatus`）。
 *
 * 注意有个 `ORPHAN_FINISH`：只收到「结束任务」报文、却没找到对应「开始」记录。
 * 这是真实存在的数据缺失场景（车端重启后丢了 start 报文，或结束时报了别的 taskId），
 * 前端必须显式展示，否则这类任务会静静地躺在列表里被误认为「还没结束」。
 */
export type TrackTaskStatus = 'RUNNING' | 'FINISHED' | 'ORPHAN_FINISH' | string

export interface TrackTask extends BaseEntity {
  id: number
  taskId: string
  enterpriseId?: string
  vin: string
  startPosition?: string
  startLatitude?: number | null
  startLongitude?: number | null
  endPosition?: string
  endLatitude?: number | null
  endLongitude?: number | null
  startedAt?: TimeValue
  finishedAt?: TimeValue
  status: TrackTaskStatus
  lastMsgId?: string
  durationSeconds?: number | null
}

/** 远程驾驶记录。`closed=false` 表示接管仍在进行中。 */
export interface RemoteDrivingRecord extends BaseEntity {
  id: number
  enterpriseId?: string
  vin: string
  /** 文档定义的驾驶类型（如接管发起/接管结束） */
  drivingType?: string
  driver?: string
  driverCard?: string
  remoteCockpitNo?: string
  occurredAt?: TimeValue
  msgId?: string
  durationSeconds?: number | null
  closed: boolean
}

/** 远驾发起接口的回执（EnterpriseApiController.remoteCockpitStart）。 */
export interface RemoteCockpitStartResult {
  vin: string
  cockpitNo?: string
  driver?: string
  msgId?: string
  accepted?: boolean
  [key: string]: unknown
}

/**
 * 远驾舱资源（`AppProperties.RemoteDriving.Cockpit`）。
 *
 * 字段名与配置项一致（`cockpitId` 而不是 `cockpitNo`）—— 这是最容易写错的地方：
 * 实体 `RemoteDrivingRecord` 里叫 `remoteCockpitNo`，而资源池里叫 `cockpitId`，
 * 两个不同的东西，混用会拿到 `undefined` 且不报错。
 */
export interface CockpitResource {
  cockpitId: string
  driver?: string
  identityNumber?: string
  /** 驾驶证号 */
  driverLicenseNumber?: string
  mobile?: string
}

/**
 * 车辆当前占用的舱位（`OperationController.currentCockpit`）。
 *
 * ⚠️ 只返回 `cockpitId`，**不**返回驾驶员/舱位详情 —— 那是资源池的信息，
 * 当前占用关系只记「车 → 舱」的绑定。`cockpitId` 为 `null` 表示当前无接管。
 */
export interface CockpitBinding {
  vin: string
  cockpitId: string | null
}

/**
 * 车端导航路径（`groute`，实体 `NavigationRoute`）。
 *
 * `pathPoints` 是**字符串**（`[[lat,lng],...]` 的 JSON 文本），后端整体存
 * mediumtext，点数千量级。前端要用时先 `JSON.parse`，再映射成 `[lng, lat]`
 * （注意实体里存的是 lat 在前，而 ECharts 的 geo/line 需要 lng 在前）。
 */
export interface NavigationRoute extends BaseEntity {
  id: number
  enterpriseId?: string
  vin: string
  msgId?: string
  reportedAt?: TimeValue
  /** `[[lat,lng],...]` 的 JSON 字符串，首元素起点、末元素终点 */
  pathPoints?: string
  pointCount?: number | null
  startLatitude?: number | null
  startLongitude?: number | null
  endLatitude?: number | null
  endLongitude?: number | null
  currentLatitude?: number | null
  currentLongitude?: number | null
}

// ==================== 地图增强 ====================

export interface MapBarrier extends BaseEntity {
  id: number
  enterpriseId?: string
  areaCode?: string
  barrierId: string
  barrierType?: string
  barrierName?: string
  /** GeoJSON 或坐标串，由监管平台下发，前端不解析（见 MapBarrierView 说明） */
  barrierPolygon?: string
  dateType?: string
  timeRange?: string
  speedLimit?: number | null
  /** LocalDate → 数组形态 [年,月,日] */
  effectiveDate?: TimeValue
  source?: string
  msgId?: string
  receivedAt?: TimeValue
  acked?: boolean
}

// ==================== 媒体 / 视频 ====================

export interface MediaAsset extends BaseEntity {
  id: number
  vin?: string
  eventId?: string
  cameraDirection?: string
  mediaType?: string
  url?: string
  storagePath?: string
  startTime?: TimeValue
  endTime?: TimeValue
  fileSize?: number | null
  kind?: string
  status?: string
  uploadedBy?: string
  requestMsgId?: string
}

/** 实时视频拉流结果（EnterpriseApiController.liveVideo）。 */
export interface LiveVideoResult {
  vin: string
  streamType?: string
  playUrls?: string[]
  url?: string
  expiresAt?: TimeValue
  [key: string]: unknown
}

// ==================== 链路监控 ====================

export interface MqttMessageLog {
  id: number
  topic: string
  /** UP / DOWN */
  direction?: string
  mqttType?: string
  senderId?: string
  msgId?: string
  payload?: string
  sizeBytes?: number
  receivedAt?: TimeValue
  processStatus?: string
  ackCode?: string
  errorMessage?: string
  latencyMs?: number
  clockSkewMs?: number | null
}

/** MQTT 链路总览指标（MqttMonitorController.mqttMetrics）。 */
export interface MqttMetrics {
  enabled: boolean
  brokerUrl: string
  clientId: string
  state: string
  connected: boolean
  reconnectAttempts: number
  published: number
  publishFailed: number
  inboundQueueSize: number
  inboundDropped: number
  inboundProcessed: number
  inboundFailed: number
  consumerThreads: number
  consumerVirtualThreads?: boolean
  offlineQueueSize: number
  offlineDroppedByPressure: number
  offlineDroppedLowPriority: number
  pendingAck: number
  pendingRetry: number
  telemetryBuffered: number
  telemetryDropped: number
  trackPointsPersisted: number
  stateSnapshotsPersisted: number
}

/** 报文统计（MqttAuditService.AuditStats）。 */
export interface MqttAuditStats {
  windowMinutes?: number
  total: number
  byType?: Record<string, number>
  byStatus?: Record<string, number>
  byDirection?: Record<string, number>
  [key: string]: unknown
}

/** 二级缓存 + 限流运行态（MqttMonitorController.cacheMetrics）。 */
export interface CacheMetrics {
  mode: string
  declaredCaches: string[]
  caches: Array<{
    name: string
    distributed?: boolean
    l1Hits?: number
    l2Hits?: number
    misses?: number
    writes?: number
    hitRate?: number
    type?: string
  }>
  rateLimit: {
    enabled: boolean
    httpPerMinute: number
    mqttPerSecond: number
    /** 限流算法标识：sliding-window-counter */
    algorithm: string
  }
}

// ==================== 总览统计 ====================

export interface DashboardOverview {
  vehicleTotal: number
  vehicleOnline: number
  vehicleOffline: number
  vehicleOnlineRate: number
  vehicleByType: Record<string, number>
  accidentToday: number
  accidentYesterday: number
  accidentPendingMedia: number
  faultToday: number
  faultSevereToday: number
  faultTopCodes: FaultCodeStat[]
  taskRunning: number
  taskToday: number
  remoteDrivingToday: number
  remoteDrivingActive: number
  mapBarrierToday: number
  mqttRecords24h: number
  mqttInboundQueue: number
  mqttInboundDropped: number
  mqttOfflineQueue: number
  mqttPendingAck: number
  generatedAt: TimeValue
}

/** 故障码统计（EventService.topFaultCodes）。 */
export interface FaultCodeStat {
  errorCode: string
  count: number
  name?: string
  category?: string
  categoryLabel?: string
}

export interface TrendPoint {
  /** yyyy-MM-dd */
  date: string
  accident: number
  fault: number
}

export interface FaultCategoryStat {
  category: string
  count: number
}

export interface FaultVehicleStat {
  vin: string
  count: number
}

/** 健康检查。 */
export interface HealthCheckResult {
  status: string
  mqttConnected: boolean
}

// ==================== 远程驾驶舱位配置 ====================

export interface CockpitInfo {
  vin: string
  cockpitNo?: string
  driver?: string
  driverCard?: string
  mobile?: string
  online?: boolean
  [key: string]: unknown
}

/** 登录结果。 */
export interface LoginResult {
  token: string
  username: string
  usingDefaultCredential?: boolean
  notice?: string
}

// ==================== 轨迹回放 ====================

/** 抽稀后的轨迹点（TrackQueryService.TrackPoint）。 */
export interface TrackPoint {
  ts: number
  longitude: number | null
  latitude: number | null
  altitude: number | null
  speed: number | null
  heading: number | null
  drivingMode: number | null
}

/**
 * 轨迹查询结果（TrackQueryService.TrackResult）。
 *
 * 后端已做 Douglas-Peucker 抽稀：`rawCount` 是原始点数、`returnedCount` 是返回点数，
 * 前端把这两个值展示出来，运维才能判断「轨迹看着不对」到底是抽稀过度还是真的丢了数据。
 */
export interface TrackResult {
  points: TrackPoint[]
  rawCount: number
  returnedCount: number
  /** 抽稀容差（米），与 dssad.api.track-simplify-tolerance 一致 */
  tolerance: number
  vin: string
  fromMs: number
  toMs: number
}

// ==================== 地图阻断设施 ====================

/**
 * 阻断设施视图（MapBarrierService.BarrierView）。
 *
 * `barrierPolygon` 是 `[[经度, 纬度], ...]` 的坐标环，`timeRange` 是
 * `[["08:00","10:00"], ...]` 的生效时段。前端据此直接绘制图形，无需地图底图。
 */
export interface BarrierView {
  barrierId: string
  barrierType?: string
  barrierTypeLabel?: string
  barrierName?: string
  areaCode?: string
  barrierPolygon?: number[][]
  dateType?: string
  timeRange?: string[][]
  speedLimit?: number | null
  source?: string
  acked?: boolean
}

export interface BarrierSummary {
  date: string
  count: number
  [key: string]: unknown
}

