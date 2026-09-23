<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { dictApi, monitorApi } from '@/api'
import type { CacheMetrics, MqttAuditStats, MqttMessageLog, MqttMetrics, MqttTypeEntry } from '@/api/types'
import EChart from '@/components/EChart.vue'
import StatCard from '@/components/StatCard.vue'
import { formatBytes, formatDecimal, formatNumber, formatRelative, formatTime } from '@/utils/format'

/**
 * 链路监控（平台的「黑匣子」页）。
 *
 * ============================ 这一页为什么必须存在 ============================
 * 平台的数据全部经 MQTT 进来，链路出问题时**不会抛异常**：
 * 队列积压 → 下游处理延迟；消费失败 → 数据静默丢失；缓存被穿透 → 接口变慢但结果正确。
 * 这三类故障没有任何一条错误日志，只能靠指标提前发现。
 *
 * 监控对象的三个面（与后端 MqttMonitorController 的分组一致）：
 *   1. **连接面**：Broker 通不通、重连多少次、发布有没有失败；
 *   2. **管道面**：入站队列 / 离线补传队列 / 待 ACK 的水位 —— 持续增长即为异常；
 *   3. **数据面**：报文留痕检索 + 按 msgId 全链路追溯（对账、自证的唯一手段）。
 *
 * 轮询间隔取 10 秒：这一页本质是「看趋势」而不是「看瞬时」，
 * 比业务页更快没有额外价值，反而会让监控本身成为负载。
 */

const mqtt = ref<MqttMetrics | null>(null)
const stats = ref<MqttAuditStats | null>(null)
const cache = ref<CacheMetrics | null>(null)

const messageRows = ref<MqttMessageLog[]>([])
const messageTotal = ref(0)
const loadingMetrics = ref(false)
const loadingMessages = ref(false)
const loadingCache = ref(false)

const mqttTypes = ref<MqttTypeEntry[]>([])

/** 报文检索条件（与后端 /monitor/messages 的 query 参数一一对应）。 */
const query = reactive({
  senderId: '',
  mqttType: '',
  processStatus: '',
  page: 1,
  size: 20
})

/** 报文统计窗口（分钟）。默认 5 分钟：够短，能反映「刚刚发生了什么」。 */
const statsWindow = ref(5)

/** 追溯弹窗状态。 */
const traceVisible = ref(false)
const traceLoading = ref(false)
const traceMsgId = ref('')
const traceRows = ref<MqttMessageLog[]>([])

/** 展开的报文体行（由表格实例托管，这里只保留引用以便「报文体」按钮联动）。 */
const messageTable = ref<{ toggleRowExpansion: (row: MqttMessageLog) => void } | null>(null)

let timer: number | undefined

// ==================== 数据加载 ====================

async function loadMetrics() {
  loadingMetrics.value = true
  try {
    const [metricsResult, statsResult] = await Promise.all([
      monitorApi.mqtt(),
      monitorApi.stats(statsWindow.value)
    ])
    mqtt.value = metricsResult
    stats.value = statsResult
  } catch {
    // http 层已提示；保留上一轮数据比清空更有利于观察趋势
  } finally {
    loadingMetrics.value = false
  }
}

async function loadCache() {
  loadingCache.value = true
  try {
    cache.value = await monitorApi.cache()
  } catch {
    cache.value = null
  } finally {
    loadingCache.value = false
  }
}

async function loadMessages() {
  loadingMessages.value = true
  try {
    const result = await monitorApi.messages({
      senderId: query.senderId.trim() || undefined,
      mqttType: query.mqttType || undefined,
      processStatus: query.processStatus || undefined,
      page: query.page,
      size: query.size
    })
    messageRows.value = result.list ?? []
    messageTotal.value = result.total ?? 0
  } catch {
    messageRows.value = []
    messageTotal.value = 0
  } finally {
    loadingMessages.value = false
  }
}

async function loadDict() {
  try {
    mqttTypes.value = await dictApi.mqttTypes()
  } catch {
    mqttTypes.value = []
  }
}

function resetQuery() {
  query.senderId = ''
  query.mqttType = ''
  query.processStatus = ''
  query.page = 1
  void loadMessages()
}

async function openTrace(row: MqttMessageLog) {
  if (!row.msgId) {
    ElMessage.warning('该报文没有 msgId，无法做全链路追溯')
    return
  }
  traceMsgId.value = row.msgId
  traceVisible.value = true
  traceLoading.value = true
  try {
    traceRows.value = await monitorApi.trace(row.msgId)
  } catch {
    traceRows.value = []
  } finally {
    traceLoading.value = false
  }
}

/**
 * 展开/收起报文体。
 *
 * 借表格实例的 `toggleRowExpansion` 而不是自己维护一个「当前展开行」变量：
 * 自己维护会与用户直接点左侧箭头展开的状态不同步，出现「按钮说收起、行却是展开的」。
 */
function togglePayload(row: MqttMessageLog) {
  messageTable.value?.toggleRowExpansion(row)
}

// ==================== 派生指标 ====================

/** 队列水位告警阈值：入站队列超过 1000 说明消费端跟不上；离线队列超过 500 说明对端长时间不可达。 */
const QUEUE_WARN = 1000
const OFFLINE_WARN = 500

const inboundTone = computed(() => {
  const size = mqtt.value?.inboundQueueSize ?? 0
  return size >= QUEUE_WARN ? 'danger' : size >= QUEUE_WARN / 2 ? 'warn' : 'ok'
})

const offlineTone = computed(() => {
  const size = mqtt.value?.offlineQueueSize ?? 0
  return size >= OFFLINE_WARN ? 'danger' : size >= OFFLINE_WARN / 2 ? 'warn' : 'ok'
})

const pendingAckTone = computed(() => {
  const size = mqtt.value?.pendingAck ?? 0
  return size >= 100 ? 'danger' : size >= 20 ? 'warn' : 'ok'
})

/** 消费失败率：这才是「数据有没有丢」的直接证据，比队列长度更早暴露问题。 */
const consumeFailRate = computed(() => {
  const processed = mqtt.value?.inboundProcessed ?? 0
  const failed = mqtt.value?.inboundFailed ?? 0
  const total = processed + failed
  if (total === 0) return null
  return (failed / total) * 100
})

/** 发布失败率：反映「平台发不出去」，与消费失败率是相反方向的问题。 */
const publishFailRate = computed(() => {
  const published = mqtt.value?.published ?? 0
  const failed = mqtt.value?.publishFailed ?? 0
  const total = published + failed
  if (total === 0) return null
  return (failed / total) * 100
})

/** 管道水位条形图：把 4 个队列放在同一坐标轴上，一眼看出谁在涨。 */
const queueOption = computed(() => {
  const item = (value: number, color: string) => ({ value, itemStyle: { color } })
  const metrics = mqtt.value
  return {
    grid: { left: 96, right: 46, top: 14, bottom: 14 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', splitLine: { lineStyle: { color: '#263140' } } },
    yAxis: {
      type: 'category',
      inverse: true,
      axisLine: { lineStyle: { color: '#263140' } },
      data: ['入站队列', '离线补传队列', '待 ACK', '待重发 ACK']
    },
    series: [
      {
        type: 'bar',
        barWidth: 14,
        label: { show: true, position: 'right', color: '#d7e0ea' },
        data: [
          item(metrics?.inboundQueueSize ?? 0, '#2f81f7'),
          item(metrics?.offlineQueueSize ?? 0, '#d29922'),
          item(metrics?.pendingAck ?? 0, '#3fb950'),
          item(metrics?.pendingRetry ?? 0, '#8b9bb0')
        ]
      }
    ]
  }
})

/** 报文统计：按类型 / 按处理结果 两个维度，用条形图看分布。 */
const statsByTypeOption = computed(() => {
  const entries = Object.entries(stats.value?.byType ?? {})
  entries.sort((a, b) => b[1] - a[1])
  return {
    grid: { left: 110, right: 40, top: 14, bottom: 14 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', splitLine: { lineStyle: { color: '#263140' } } },
    yAxis: {
      type: 'category',
      inverse: true,
      axisLine: { lineStyle: { color: '#263140' } },
      data: entries.map(([key]) => key)
    },
    series: [
      {
        type: 'bar',
        barWidth: 14,
        itemStyle: { color: '#2f81f7' },
        label: { show: true, position: 'right', color: '#d7e0ea' },
        data: entries.map(([, value]) => value)
      }
    ]
  }
})

const statsByStatusOption = computed(() => {
  const entries = Object.entries(stats.value?.byStatus ?? {})
  // 处理结果有语义：OK 绿、其余按严重程度着色，让「异常占比」不用读数字就能看出
  const colorOf = (status: string) => {
    if (status === 'OK') return '#3fb950'
    if (status === 'INVALID') return '#d29922'
    if (status === 'ERROR') return '#f85149'
    return '#8b9bb0'
  }
  return {
    grid: { left: 96, right: 40, top: 14, bottom: 14 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', splitLine: { lineStyle: { color: '#263140' } } },
    yAxis: {
      type: 'category',
      inverse: true,
      axisLine: { lineStyle: { color: '#263140' } },
      data: entries.map(([key]) => key)
    },
    series: [
      {
        type: 'bar',
        barWidth: 14,
        label: { show: true, position: 'right', color: '#d7e0ea' },
        data: entries.map(([key, value]) => ({ value, itemStyle: { color: colorOf(key) } }))
      }
    ]
  }
})

// ==================== 展示辅助 ====================

function statusTagType(status?: string) {
  switch (status) {
    case 'OK':
      return 'success'
    case 'INVALID':
      return 'warning'
    case 'ERROR':
      return 'danger'
    default:
      return 'info'
  }
}

/**
 * 命中率着色。
 *
 * 门槛定在 50% / 90%：本项目大屏缓存 TTL 只有 5 秒、前端轮询 10 秒，
 * 正常命中率应当在 90% 以上；掉到 50% 以下说明 TTL 配置或轮询间隔对不上，
 * 此时数据库 QPS 已经翻倍但接口仍返回正确结果 —— 属于典型静默劣化。
 */
function hitRateTone(rate?: number) {
  if (rate === undefined) return ''
  if (rate >= 0.9) return 'text-ok'
  if (rate >= 0.5) return 'text-warn'
  return 'text-danger'
}

function typeDocRef(code?: string): string {
  if (!code) return ''
  return mqttTypes.value.find((item) => item.code === code)?.docRef ?? ''
}

function typeDescription(code?: string): string {
  if (!code) return ''
  return mqttTypes.value.find((item) => item.code === code)?.description ?? ''
}

/** 报文体可能是几百 KB 的 JSON，默认只展示前 400 字符，避免表格行高失控。 */
function previewPayload(payload?: string): string {
  if (!payload) return '（空）'
  return payload.length > 400 ? `${payload.slice(0, 400)}…` : payload
}

onMounted(() => {
  void loadMetrics()
  void loadMessages()
  void loadCache()
  void loadDict()
  timer = window.setInterval(() => {
    void loadMetrics()
    void loadCache()
  }, 10_000)
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>链路监控</h2>
        <p class="subtitle">
          监控三类「不会报错」的故障：队列积压、消费失败、缓存被穿透。指标每 10 秒自动刷新。
        </p>
      </div>
      <div class="header-actions">
        <el-select v-model="statsWindow" size="small" style="width: 110px" @change="loadMetrics">
          <el-option label="近 5 分钟" :value="5" />
          <el-option label="近 30 分钟" :value="30" />
          <el-option label="近 60 分钟" :value="60" />
        </el-select>
        <el-button size="small" :loading="loadingMetrics" @click="loadMetrics">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
      </div>
    </div>

    <!-- ========== 连接面 + 管道面 ========== -->
    <div class="stat-grid">
      <StatCard
        label="Broker 连接"
        :value="mqtt?.connected ? '已连接' : '未连接'"
        :tone="mqtt?.connected ? 'ok' : 'danger'"
        :hint="mqtt?.state ? `状态机：${mqtt.state}` : '等待指标返回'"
        icon="Link"
      />
      <StatCard
        label="发布成功"
        :value="formatNumber(mqtt?.published ?? 0)"
        unit="条"
        hint="平台 → 车端/监管平台的累计出站报文"
        icon="Promotion"
      />
      <StatCard
        label="发布失败"
        :value="formatNumber(mqtt?.publishFailed ?? 0)"
        unit="条"
        :tone="(mqtt?.publishFailed ?? 0) > 0 ? 'danger' : 'default'"
        :hint="publishFailRate === null ? '暂无出站样本' : `失败率 ${formatDecimal(publishFailRate, 2)}%`"
        icon="WarningFilled"
      />
      <StatCard
        label="入站队列"
        :value="formatNumber(mqtt?.inboundQueueSize ?? 0)"
        unit="条"
        :tone="inboundTone"
        :hint="`积压过半即需关注（阈值 ${QUEUE_WARN}）`"
        icon="Tickets"
      />
      <StatCard
        label="已消费"
        :value="formatNumber(mqtt?.inboundProcessed ?? 0)"
        unit="条"
        hint="进入业务 Handler 并处理完成的报文"
        icon="CircleCheck"
      />
      <StatCard
        label="消费失败"
        :value="formatNumber(mqtt?.inboundFailed ?? 0)"
        unit="条"
        :tone="(mqtt?.inboundFailed ?? 0) > 0 ? 'danger' : 'ok'"
        :hint="consumeFailRate === null ? '暂无消费样本' : `失败率 ${formatDecimal(consumeFailRate, 2)}%`"
        icon="CircleClose"
      />
      <StatCard
        label="离线补传队列"
        :value="formatNumber(mqtt?.offlineQueueSize ?? 0)"
        unit="条"
        :tone="offlineTone"
        :hint="`丢压 ${formatNumber(mqtt?.offlineDroppedByPressure ?? 0)} / 丢低优 ${formatNumber(mqtt?.offlineDroppedLowPriority ?? 0)}`"
        icon="Upload"
      />
      <StatCard
        label="待 ACK"
        :value="formatNumber(mqtt?.pendingAck ?? 0)"
        unit="条"
        :tone="pendingAckTone"
        :hint="`待重发 ${formatNumber(mqtt?.pendingRetry ?? 0)}（超时未收到对端回复）`"
        icon="Clock"
      />
      <StatCard
        label="遥测缓冲"
        :value="formatNumber(mqtt?.telemetryBuffered ?? 0)"
        :tone="(mqtt?.telemetryDropped ?? 0) > 0 ? 'warn' : 'default'"
        :hint="`丢弃 ${formatNumber(mqtt?.telemetryDropped ?? 0)}（批量攒批写入用）`"
        icon="Odometer"
      />
      <StatCard
        label="轨迹点入库"
        :value="formatNumber(mqtt?.trackPointsPersisted ?? 0)"
        unit="点"
        hint="抽稀前的原始轨迹点累计落库量"
        icon="Location"
      />
      <StatCard
        label="状态快照入库"
        :value="formatNumber(mqtt?.stateSnapshotsPersisted ?? 0)"
        unit="条"
        hint="运行状态分页查询的数据源"
        icon="DataLine"
      />
      <StatCard
        label="消费线程"
        :value="formatNumber(mqtt?.consumerThreads ?? 0)"
        unit="个"
        tone="ok"
        :hint="mqtt?.consumerVirtualThreads ? '虚拟线程（Java 21，IO 等待不占平台线程）' : '平台线程'"
        icon="Cpu"
      />
    </div>

    <div class="panel queue-panel">
      <h3 class="panel-title">管道水位</h3>
      <p class="muted note">
        积压类指标是「早期信号」：队列只会越攒越多，等业务表里查不到数据时，
        故障已经发生了几十分钟。离线补传队列不为 0 说明对端曾长时间不可达；
        待 ACK 不为 0 说明有人在等回复。
      </p>
      <EChart :option="queueOption" height="170px" />
    </div>

    <!-- ========== 报文统计 ========== -->
    <div class="chart-grid-2">
      <div class="panel">
        <h3 class="panel-title">报文类型分布（{{ stats?.windowMinutes ?? statsWindow }} 分钟内）</h3>
        <EChart :option="statsByTypeOption" height="260px" empty-text="窗口内没有报文" />
      </div>
      <div class="panel">
        <h3 class="panel-title">处理结果分布</h3>
        <p class="muted note stat-total">窗口内共 {{ formatNumber(stats?.total ?? 0) }} 条</p>
        <EChart :option="statsByStatusOption" height="260px" empty-text="窗口内没有报文" />
      </div>
    </div>

    <!-- ========== 缓存与限流 ========== -->
    <div class="panel">
      <h3 class="panel-title">
        缓存与限流运行态
        <el-tag v-if="cache" size="small" effect="plain">{{ cache.mode }}</el-tag>
      </h3>
      <p class="muted note">
        缓存失效是<b>静默</b>的：命中率从 95% 掉到 0 时接口依然返回正确结果，只是数据库 QPS 翻几倍
        —— 表现为「大屏越来越慢」而不是任何一条错误日志。命中率低于 50% 即为劣化。
      </p>

      <el-table
        v-loading="loadingCache"
        :data="cache?.caches ?? []"
        size="small"
        empty-text="缓存未启用或未声明任何缓存区"
      >
        <el-table-column prop="name" label="缓存区" min-width="180">
          <template #default="{ row }">
            <span class="mono">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="形态" width="180">
          <template #default="{ row }">
            <el-tag size="small" :type="row.distributed ? 'success' : 'info'" effect="plain">
              {{ row.distributed ? 'L1 + L2 分布式' : 'L1 单机' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="L1 命中" width="100" align="right">
          <template #default="{ row }">{{ formatNumber(row.l1Hits ?? 0) }}</template>
        </el-table-column>
        <el-table-column label="L2 命中" width="100" align="right">
          <template #default="{ row }">{{ formatNumber(row.l2Hits ?? 0) }}</template>
        </el-table-column>
        <el-table-column label="未命中" width="100" align="right">
          <template #default="{ row }">{{ formatNumber(row.misses ?? 0) }}</template>
        </el-table-column>
        <el-table-column label="写入" width="100" align="right">
          <template #default="{ row }">{{ formatNumber(row.writes ?? 0) }}</template>
        </el-table-column>
        <el-table-column label="命中率" width="140" align="right">
          <template #default="{ row }">
            <span :class="hitRateTone(row.hitRate)">
              {{ row.hitRate === undefined ? '—' : `${(row.hitRate * 100).toFixed(1)}%` }}
            </span>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="cache" class="rate-limit">
        <el-descriptions :column="4" size="small" border>
          <el-descriptions-item label="限流开关">
            <el-tag size="small" :type="cache.rateLimit.enabled ? 'success' : 'info'" effect="dark">
              {{ cache.rateLimit.enabled ? '已启用' : '已关闭' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="HTTP 限额">
            {{ formatNumber(cache.rateLimit.httpPerMinute) }} 次/分钟·车
          </el-descriptions-item>
          <el-descriptions-item label="MQTT 发布限额">
            {{ formatNumber(cache.rateLimit.mqttPerSecond) }} 次/秒·车
          </el-descriptions-item>
          <el-descriptions-item label="限流算法">
            <span class="mono">{{ cache.rateLimit.algorithm }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <p class="muted note">
          算法口径必须显式可见：固定窗口与滑动窗口的突发容忍度不同，
          不知道实际算法时，很容易把「文档口径被突破」误判成缺陷（反之亦然）。
        </p>
      </div>
    </div>

    <!-- ========== 报文留痕 ========== -->
    <div class="panel">
      <h3 class="panel-title">报文留痕</h3>
      <p class="muted note">
        全量留痕的意义在于「自证」：发生事故或监管核查时，必须能说清某时刻收到了什么、回复了什么、耗时多久。
        点击「追溯」可按 msgId 还原「上行 → 下行回复 → 重发」的完整时序。
      </p>

      <el-form :inline="true" class="filters" @submit.prevent="loadMessages">
        <el-form-item label="发送方">
          <el-input
            v-model="query.senderId"
            placeholder="VIN 或企业 ID"
            clearable
            style="width: 200px"
            @keyup.enter="loadMessages"
          />
        </el-form-item>
        <el-form-item label="报文类型">
          <el-select v-model="query.mqttType" placeholder="全部" clearable style="width: 180px">
            <el-option
              v-for="item in mqttTypes"
              :key="item.code"
              :label="`${item.code}（${item.description ?? ''}）`"
              :value="item.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="处理结果">
          <el-select v-model="query.processStatus" placeholder="全部" clearable style="width: 140px">
            <el-option label="OK 正常" value="OK" />
            <el-option label="INVALID 校验失败" value="INVALID" />
            <el-option label="UNSUPPORTED 不支持" value="UNSUPPORTED" />
            <el-option label="ERROR 处理异常" value="ERROR" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadMessages">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table
        ref="messageTable"
        v-loading="loadingMessages"
        :data="messageRows"
        size="small"
        empty-text="没有符合条件的报文"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="payload-box">
              <div class="payload-head">
                <span>报文体</span>
                <span class="muted">
                  {{ formatBytes(row.sizeBytes) }} · Topic <span class="mono">{{ row.topic }}</span>
                </span>
              </div>
              <pre class="payload mono">{{ row.payload ?? '（未留痕报文体）' }}</pre>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="接收时间" width="170">
          <template #default="{ row }">
            <div>{{ formatTime(row.receivedAt) }}</div>
            <div class="muted tiny">{{ formatRelative(row.receivedAt) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="方向" width="72">
          <template #default="{ row }">
            <el-tag size="small" :type="row.direction === 'UP' ? 'success' : 'warning'" effect="plain">
              {{ row.direction === 'UP' ? '上行' : '下行' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="150">
          <template #default="{ row }">
            <div class="mono">{{ row.mqttType || '—' }}</div>
            <div v-if="typeDescription(row.mqttType)" class="muted tiny">
              {{ typeDescription(row.mqttType) }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="发送方" width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.senderId || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="处理结果" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row.processStatus)" effect="dark">
              {{ row.processStatus || '—' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90" align="right">
          <template #default="{ row }">
            <span :class="{ 'text-warn': (row.latencyMs ?? 0) > 500 }">
              {{ row.latencyMs === null || row.latencyMs === undefined ? '—' : `${row.latencyMs} ms` }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="时钟偏差" width="110" align="right">
          <template #default="{ row }">
            <span
              v-if="row.clockSkewMs === null || row.clockSkewMs === undefined"
              class="muted"
            >—</span>
            <span v-else :class="{ 'text-warn': Math.abs(row.clockSkewMs) > 5000 }">
              {{ row.clockSkewMs }} ms
            </span>
          </template>
        </el-table-column>
        <el-table-column label="错误摘要" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="text-danger">{{ row.errorMessage || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openTrace(row)">追溯</el-button>
            <el-button link size="small" @click="togglePayload(row)">报文体</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        class="pager"
        :total="messageTotal"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="loadMessages"
        @size-change="loadMessages"
      />
    </div>

    <!-- ========== 全链路追溯 ========== -->
    <el-dialog v-model="traceVisible" title="全链路追溯" width="880px" top="6vh">
      <p class="muted note">
        msgId：<span class="mono">{{ traceMsgId }}</span>
        —— 下面按接收时间升序，天然体现「上行 → 下行回复 → 重发」的时序。
      </p>
      <div v-loading="traceLoading" class="trace-body">
        <el-table :data="traceRows" size="small" empty-text="未找到该 msgId 的留痕记录">
          <el-table-column label="#" type="index" width="52" />
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ formatTime(row.receivedAt) }}</template>
          </el-table-column>
          <el-table-column label="方向" width="72">
            <template #default="{ row }">
              <el-tag size="small" :type="row.direction === 'UP' ? 'success' : 'warning'" effect="plain">
                {{ row.direction === 'UP' ? '上行' : '下行' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="150">
            <template #default="{ row }">
              <span class="mono">{{ row.mqttType || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTagType(row.processStatus)" effect="dark">
                {{ row.processStatus || '—' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="ACK" width="80">
            <template #default="{ row }">{{ row.ackCode || '—' }}</template>
          </el-table-column>
          <el-table-column label="Topic" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="mono">{{ row.topic }}</span>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="traceRows.length" class="trace-detail">
          <div v-for="row in traceRows" :key="row.id" class="trace-item">
            <div class="trace-item-head">
              <span class="mono">{{ row.mqttType }}</span>
              <span class="muted">{{ formatTime(row.receivedAt) }}</span>
              <span v-if="typeDocRef(row.mqttType)" class="muted">文档 {{ typeDocRef(row.mqttType) }}</span>
            </div>
            <pre class="payload mono">{{ previewPayload(row.payload) }}</pre>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="traceVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.note {
  margin: 0 0 10px;
  font-size: 12.5px;
  line-height: 1.75;
}

.stat-total {
  margin-top: -8px;
}

.queue-panel {
  margin-top: 16px;
}

.rate-limit {
  margin-top: 16px;
}

.filters {
  margin-bottom: 4px;
}

.filters :deep(.el-form-item) {
  margin-bottom: 12px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.tiny {
  font-size: 11px;
  line-height: 1.4;
}

.payload-box {
  padding: 6px 14px 10px;
}

.payload-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--dssad-text-dim);
  margin-bottom: 6px;
}

.payload {
  margin: 0;
  padding: 10px 12px;
  background: #0b0f15;
  border: 1px solid var(--dssad-border);
  border-radius: 6px;
  color: #9fd3a1;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 260px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.6;
}

.trace-body {
  min-height: 200px;
}

.trace-detail {
  margin-top: 14px;
}

.trace-item + .trace-item {
  margin-top: 10px;
}

.trace-item-head {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  margin-bottom: 5px;
}
</style>
