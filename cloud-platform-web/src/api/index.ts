import { get, post, upload } from './http'
import type {
  AccidentDetail,
  AccidentEvent,
  AccidentMediaStatus,
  BarrierSummary,
  BarrierView,
  CacheMetrics,
  CockpitBinding,
  CockpitResource,
  DashboardOverview,
  DictEntry,
  ErrorCodeEntry,
  FaultCategoryStat,
  FaultCodeEntry,
  FaultCodeStat,
  FaultDetail,
  FaultRecord,
  FaultVehicleStat,
  HealthCheckResult,
  LoginResult,
  MqttAuditStats,
  MqttMessageLog,
  MqttMetrics,
  MqttTypeEntry,
  NavigationRoute,
  PageResult,
  RemoteDrivingRecord,
  TrackResult,
  TrackTask,
  TrendPoint,
  Vehicle,
  VehicleDetail,
  VehiclePosition,
  VehicleState,
  VehicleStaticParam,
  VehicleTrackPoint
} from './types'

/**
 * 平台接口封装。
 *
 * 组织方式：**按后端控制器分域**（auth / dashboard / vehicles / events /
 * operations / mapBarriers / monitor / simulator），与后端 `web/` 包一一对应。
 * 这样接口文档、后端控制器、前端封装三者的目录结构完全同构，
 * 对接时不需要在脑子里做二次映射。
 *
 * 所有函数都只做「拼参数 + 定类型」，不做任何数据处理 —— 数据整形属于页面/组合式函数的职责。
 */

// ==================== 认证 ====================

export const authApi = {
  /**
   * 登录。
   *
   * 注意：平台几乎所有 `/api/v1/**` 接口都需要 `X-Token`，
   * 未登录时服务端会返回 **HTTP 200 + 业务码 2001**（见 api/http.ts 顶部约定 1）。
   */
  login: (username: string, password: string) =>
    post<LoginResult>('/api/v1/auth/login', { username, password }),

  /** 登出（服务端吊销令牌）。 */
  logout: () => post<void>('/api/v1/auth/logout')
}

// ==================== 总览大屏 ====================

export const dashboardApi = {
  /** 总览统计（后端 TTL 5 秒缓存，前端轮询间隔应 ≥ 10 秒以免打穿缓存）。 */
  overview: () => get<DashboardOverview>('/api/v1/dashboard/overview'),

  /** 近 N 天事故/故障趋势。 */
  trend: (days = 7) => get<TrendPoint[]>('/api/v1/dashboard/trend', { days }),

  /** 故障分类分布（按附录 A.1 的 5 大类归集）。 */
  faultCategories: (days = 7) =>
    get<FaultCategoryStat[]>('/api/v1/dashboard/fault-categories', { days }),

  /** 按车辆聚合的故障榜。 */
  faultVehicles: (days = 7, limit = 10) =>
    get<FaultVehicleStat[]>('/api/v1/dashboard/fault-vehicles', { days, limit })
}

// ==================== 车辆 ====================

export const vehicleApi = {
  /** 车辆分页列表。 */
  list: (params: {
    keyword?: string
    vehicleType?: string
    online?: boolean
    page?: number
    size?: number
  }) => get<PageResult<Vehicle>>('/api/v1/vehicles', params),

  /**
   * 车辆详情：返回 `{vehicle, latestState, position}`。
   *
   * 注意车辆字段嵌在 `vehicle` 下（见 types.ts 的 VehicleDetail 说明）。
   */
  detail: (vin: string) => get<VehicleDetail>(`/api/v1/vehicles/${vin}`),

  /** 最新运行状态（读缓存，未命中回落数据库）。 */
  state: (vin: string) => get<VehicleState | null>(`/api/v1/vehicles/${vin}/state`),

  /** 当前位置（读缓存，仅最近 30 分钟内有上报才有值）。 */
  position: (vin: string) => get<VehiclePosition | null>(`/api/v1/vehicles/${vin}/position`),

  /** 运行状态历史分页。 */
  stateHistory: (vin: string, params: { hours?: number; page?: number; size?: number }) =>
    get<PageResult<VehicleState>>(`/api/v1/vehicles/${vin}/state-history`, params),

  /**
   * 轨迹查询（含 Douglas-Peucker 抽稀）。
   *
   * `startTime` / `endTime` 必须是**毫秒时间戳**（后端签名为 `long`）。
   */
  track: (vin: string, params: { startTime: number; endTime: number; maxPoints?: number }) =>
    get<TrackResult>(`/api/v1/vehicles/${vin}/track`, params),

  /** 准静态参数变更历史。 */
  staticParams: (vin: string, params: { page?: number; size?: number }) =>
    get<PageResult<VehicleStaticParam>>(`/api/v1/vehicles/${vin}/static-params`, params),

  /** 轨迹原始点（未经抽稀，仅用于对比验证）。 */
  rawTrack: (vin: string) =>
    get<VehicleTrackPoint[]>(`/api/v1/vehicles/${vin}/state-history`, { hours: 1 })
}

// ==================== 事件（事故 / 故障） ====================

export const eventApi = {
  /** 事故列表。`mediaStatus` 必须传枚举名（如 MEDIA_REQUESTED）。 */
  accidents: (params: {
    vin?: string
    mediaStatus?: AccidentMediaStatus
    hours?: number
    page?: number
    size?: number
  }) => get<PageResult<AccidentEvent>>('/api/v1/events/accidents', params),

  /**
   * 事故详情（含关联媒体 + 取证要素是否齐备）。
   *
   * 返回 `{event, media, mediaComplete}`；`mediaComplete` 的口径由后端判定（见 types.ts），
   * 前端不要自己用 `media.length >= 4` 重算 —— 取证门槛属于监管口径，只能有一处定义。
   */
  accidentDetail: (eventId: string) =>
    get<AccidentDetail>(`/api/v1/events/accidents/${eventId}`),

  /** 超时未收到取证的挂起事故（运维催办清单）。 */
  pendingAccidents: (params?: { timeoutMinutes?: number; limit?: number }) =>
    get<AccidentEvent[]>('/api/v1/events/accidents/pending', params),

  /** 故障列表。`vin` 与 `minSeverity` 可组合（后端已支持任意条件组合）。 */
  faults: (params: {
    vin?: string
    minSeverity?: number
    hours?: number
    page?: number
    size?: number
  }) => get<PageResult<FaultRecord>>('/api/v1/events/faults', params),

  /** 故障明细（record + items + itemCount）。 */
  faultDetail: (id: number) => get<FaultDetail>(`/api/v1/events/faults/${id}`),

  /** 故障码 TOP N。 */
  topFaultCodes: (days = 7, limit = 10) =>
    get<FaultCodeStat[]>('/api/v1/events/faults/top-codes', { days, limit })
}

// ==================== 运营（任务 / 远驾 / 路线） ====================

export const operationApi = {
  /** 轨迹任务分页。 */
  tasks: (params: { vin?: string; status?: string; page?: number; size?: number }) =>
    get<PageResult<TrackTask>>('/api/v1/operations/tasks', params),

  /**
   * 下发/结束轨迹任务。
   *
   * 后端会「同时上报监管平台 + 本地落库」，因此返回的任务对象里
   * `status` 已经是落库后的真实状态。
   *
   * ⚠️ `action` 在后端是 `@NotBlank`（必填），协议取值为
   * `1` = 开始任务、`2` = 结束任务 —— 这是最容易漏传的参数：
   * 漏了它后端直接返回 1001，而前端如果不看业务码会把「参数错误」显示成「下发成功」。
   */
  dispatchTask: (body: {
    vin: string
    /** 1 开始 / 2 结束（对应协议 taskAction） */
    action: string
    taskId?: string
    startPosition?: string
    startPoint?: [number, number]
    endPosition?: string
    endPoint?: [number, number]
  }) => post<TrackTask>('/api/v1/operations/tasks', body),

  /** 远驾记录分页。`closed=false` 即「进行中接管」。 */
  remoteDriving: (params: { vin?: string; closed?: boolean; page?: number; size?: number }) =>
    get<PageResult<RemoteDrivingRecord>>('/api/v1/operations/remote-driving', params),

  /** 当前接管舱位（只返回 `{vin, cockpitId}`，cockpitId 为 null 即无接管）。 */
  currentCockpit: (vin: string) =>
    get<CockpitBinding>(`/api/v1/operations/vehicles/${vin}/cockpit`),

  /**
   * 发起远程接管（平台内部入口，区别于监管平台调用的云云接口）。
   *
   * `starter` 取值被后端强校验：`1` 交巡警 / `2` 监管平台 —— 传其它值直接返回 1001。
   * 后端按 LRU 分配远驾舱，并同步上报监管平台 + 本地落记录（不等对端 ACK）。
   */
  startCockpit: (vin: string, starter = '2') =>
    post<CockpitResource>(
      `/api/v1/operations/vehicles/${vin}/cockpit/start?starter=${encodeURIComponent(starter)}`
    ),

  /**
   * 结束远程接管。
   *
   * 返回 `false` 表示「本来就没有进行中的接管」—— 这不是接口失败，
   * 而是状态不一致的提示（车端已断链、平台重启丢了状态存储等），
   * 前端应如实展示而不是笼统提示「成功」。
   */
  endCockpit: (vin: string) => post<boolean>(`/api/v1/operations/vehicles/${vin}/cockpit/end`),

  /** 导航路线分页。 */
  routes: (vin: string, params: { page?: number; size?: number }) =>
    get<PageResult<NavigationRoute>>('/api/v1/operations/routes', { vin, ...params }),

  /** 最新导航路线。 */
  latestRoute: (vin: string) =>
    get<NavigationRoute | null>('/api/v1/operations/routes/latest', { vin })
}

// ==================== 地图阻断设施 ====================

export const mapBarrierApi = {
  /** 今日全量（地图图层用）。 */
  today: () => get<BarrierView[]>('/api/v1/map-barriers'),

  /** 今日分页（列表视图用）。 */
  page: (params: { page?: number; size?: number }) =>
    get<PageResult<BarrierView>>('/api/v1/map-barriers/page', params),

  /** 指定日期（事故复盘时还原当时路况）。 `date` 为 yyyy-MM-dd。 */
  byDate: (date: string) => get<BarrierView[]>('/api/v1/map-barriers/by-date', { date }),

  /** 统计信息（今日条数）。 */
  summary: () => get<BarrierSummary>('/api/v1/map-barriers/summary'),

  /** 立即从监管平台拉取（不必等到次日 00:05 的定时任务）。 */
  pull: (areaCode?: string) =>
    post<{ fetched: number }>(
      `/api/v1/map-barriers/pull${areaCode ? `?areaCode=${encodeURIComponent(areaCode)}` : ''}`
    )
}

// ==================== 链路监控 ====================

export const monitorApi = {
  healthCheck: () => get<HealthCheckResult>('/api/v1/monitor/health-check'),

  /** MQTT 链路总览（连接面 + 管道面 + 遥测缓冲）。 */
  mqtt: () => get<MqttMetrics>('/api/v1/monitor/mqtt'),

  /** 报文统计（默认近 5 分钟）。 */
  stats: (windowMinutes = 5) =>
    get<MqttAuditStats>('/api/v1/monitor/mqtt/stats', { windowMinutes }),

  /** 报文留痕分页。 */
  messages: (params: {
    senderId?: string
    mqttType?: string
    processStatus?: string
    page?: number
    size?: number
  }) => get<PageResult<MqttMessageLog>>('/api/v1/monitor/messages', params),

  /** 按 msgId 追溯全链路（上行 → 下行回复 → 重发的完整时序）。 */
  trace: (msgId: string) => get<MqttMessageLog[]>(`/api/v1/monitor/messages/trace/${msgId}`),

  /** 二级缓存命中率 + 限流配置。 */
  cache: () => get<CacheMetrics>('/api/v1/monitor/cache')
}

// ==================== 数据字典 ====================

export const dictApi = {
  faultCodes: () => get<FaultCodeEntry[]>('/api/v1/dict/fault-codes'),
  faultCategories: () => get<DictEntry[]>('/api/v1/dict/fault-categories'),
  cameraDirections: () => get<DictEntry[]>('/api/v1/dict/camera-directions'),
  mqttTypes: () => get<MqttTypeEntry[]>('/api/v1/dict/mqtt-types'),
  /** 错误码字典：前端可据此把 code 映射成可读提示（后端已内置 message）。 */
  errorCodes: () => get<ErrorCodeEntry[]>('/api/v1/dict/error-codes'),
  streamTypes: () => get<DictEntry[]>('/api/v1/dict/stream-types')
}

// ==================== 事故视频取证上传 ====================

export const mediaApi = {
  /**
   * 上传事故取证视频（监管平台侧回调入口）。
   *
   * 后端的 5 个参数全部是 **query 参数**，文件走 multipart body ——
   * 这是最容易被前端写错的一点（习惯性地把 vin/file 一起塞进 FormData）。
   */
  uploadAccidentMedia: (
    params: {
      vin: string
      msgId: string
      ackMsgId: string
      eventId: string
      cameraDirection: string
    },
    file: File,
    onProgress?: (percent: number) => void
  ) => {
    const form = new FormData()
    form.append('file', file)
    const query = new URLSearchParams(params).toString()
    return upload<Record<string, unknown>>(
      `/manager/api/v1/video/accident-media-upload?${query}`,
      form,
      onProgress
    )
  }
}

// ==================== 联调模拟器（仅本地/测试启用） ====================

export const simulatorApi = {
  /** 注入任意 MQTT 报文：`topic` 是 query 参数，`payload` 是裸 JSON 字符串体。 */
  inject: (topic: string, payload: string) =>
    post<Record<string, unknown>>(
      `/api/v1/simulator/mqtt?topic=${encodeURIComponent(topic)}`,
      payload
    ),

  /** 一键造一条事故（含取证请求）。 */
  scenarioAccident: (vin: string, vehicleType?: string) =>
    post<Record<string, unknown>>(
      `/api/v1/simulator/scenario/accident?vin=${encodeURIComponent(vin)}` +
        (vehicleType ? `&vehicleType=${encodeURIComponent(vehicleType)}` : '')
    ),

  /** 一键造一条故障。 */
  scenarioFault: (vin: string, vehicleType?: string) =>
    post<Record<string, unknown>>(
      `/api/v1/simulator/scenario/fault?vin=${encodeURIComponent(vin)}` +
        (vehicleType ? `&vehicleType=${encodeURIComponent(vehicleType)}` : '')
    )
}
