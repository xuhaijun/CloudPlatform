<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { dashboardApi, eventApi } from '@/api'
import type {
  DashboardOverview,
  FaultCategoryStat,
  FaultCodeStat,
  FaultVehicleStat,
  TrendPoint
} from '@/api/types'
import EChart from '@/components/EChart.vue'
import StatCard from '@/components/StatCard.vue'
import { formatNumber, formatTime } from '@/utils/format'

/**
 * 运营总览大屏。
 *
 * 两条与后端强相关的约定：
 *  1. **轮询间隔必须 ≥ 缓存 TTL**。后端总览接口带 5 秒 TTL 的二级缓存，
 *     前端按 `VITE_POLL_INTERVAL_MS`（默认 10 秒）轮询 —— 比缓存 TTL 更快地轮询
 *     不会拿到更新的数据，只会白白打穿缓存、把数据库压力放大数倍。
 *  2. **严重故障口径固定为 severity ≥ 6**（与后端 `faultSevereToday` 一致），
 *     前端不要另起一套阈值，否则大屏数字与事件中心列表会对不上。
 */
const POLL_INTERVAL = Number(import.meta.env.VITE_POLL_INTERVAL_MS || 10_000)

const overview = ref<DashboardOverview | null>(null)
const trend = ref<TrendPoint[]>([])
const categories = ref<FaultCategoryStat[]>([])
const faultVehicles = ref<FaultVehicleStat[]>([])
const topCodes = ref<FaultCodeStat[]>([])

const days = ref(7)
const loading = ref(false)
const autoRefresh = ref(true)
const lastLoadedAt = ref<number | null>(null)

let timer: number | undefined

/* ------------------------------ 数据加载 ------------------------------ */

async function loadAll() {
  loading.value = true
  try {
    const [ov, tr, cat, fv, tc] = await Promise.all([
      dashboardApi.overview(),
      dashboardApi.trend(days.value),
      dashboardApi.faultCategories(days.value),
      dashboardApi.faultVehicles(days.value, 10),
      // 故障码 TOP 走事件域接口（/events/faults/top-codes），
      // 总览接口里的 faultTopCodes 是固定「近 7 天」口径，不能跟随页面上的天数切换
      eventApi.topFaultCodes(days.value, 10)
    ])
    overview.value = ov
    trend.value = tr
    categories.value = cat
    faultVehicles.value = fv
    topCodes.value = tc
    lastLoadedAt.value = Date.now()
  } catch {
    // http 层已提示；保留上一次数据，避免刷新失败时大屏变空白
  } finally {
    loading.value = false
  }
}

/* ------------------------------ 计算属性 ------------------------------ */

const onlineRateTone = computed(() => {
  const rate = overview.value?.vehicleOnlineRate ?? 0
  if (rate >= 90) return 'ok'
  if (rate >= 60) return 'warn'
  return 'danger'
})

/** 取证积压是运维最该关注的指标：积压意味着监管取证的证据链可能超期。 */
const pendingTone = computed(() => {
  const count = overview.value?.accidentPendingMedia ?? 0
  if (count === 0) return 'ok'
  if (count <= 3) return 'warn'
  return 'danger'
})

/** 入站队列积压：持续 > 100 说明消费能力跟不上上报速率。 */
const queueTone = computed(() => {
  const size = overview.value?.mqttInboundQueue ?? 0
  if (size < 50) return 'ok'
  if (size < 500) return 'warn'
  return 'danger'
})

/* ------------------------------ 图表配置 ------------------------------ */

const trendOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['事故', '故障'], top: 0, textStyle: { color: '#8b9bb0' } },
  grid: { left: 46, right: 18, top: 36, bottom: 28 },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: trend.value.map((p) => p.date.slice(5)),
    axisLine: { lineStyle: { color: '#263140' } }
  },
  yAxis: {
    type: 'value',
    splitLine: { lineStyle: { color: '#1e2836' } },
    axisLine: { show: false }
  },
  series: [
    {
      name: '事故',
      type: 'line',
      smooth: true,
      symbolSize: 6,
      data: trend.value.map((p) => p.accident),
      itemStyle: { color: '#f85149' },
      areaStyle: { color: 'rgba(248, 81, 73, 0.14)' }
    },
    {
      name: '故障',
      type: 'line',
      smooth: true,
      symbolSize: 6,
      data: trend.value.map((p) => p.fault),
      itemStyle: { color: '#d29922' },
      areaStyle: { color: 'rgba(210, 153, 34, 0.12)' }
    }
  ]
}))

const categoryOption = computed(() => ({
  tooltip: { trigger: 'item', formatter: '{b}<br/>{c} 条（{d}%）' },
  legend: { bottom: 0, textStyle: { color: '#8b9bb0' } },
  series: [
    {
      type: 'pie',
      radius: ['46%', '68%'],
      center: ['50%', '44%'],
      avoidLabelOverlap: true,
      label: { color: '#a9b7c8', fontSize: 11 },
      data: categories.value.map((c) => ({ name: c.category, value: c.count }))
    }
  ]
}))

const vehicleOption = computed(() => {
  const ordered = [...faultVehicles.value].reverse()
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 96, right: 26, top: 12, bottom: 22 },
    xAxis: { type: 'value', splitLine: { lineStyle: { color: '#1e2836' } } },
    yAxis: {
      type: 'category',
      data: ordered.map((v) => v.vin),
      axisLine: { lineStyle: { color: '#263140' } }
    },
    series: [
      {
        type: 'bar',
        data: ordered.map((v) => v.count),
        barWidth: 12,
        itemStyle: {
          borderRadius: [0, 4, 4, 0],
          // 用渐变色代替纯色：条数多时纯色块在大屏上会显得"糊"
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 1,
            y2: 0,
            colorStops: [
              { offset: 0, color: '#1a5fbf' },
              { offset: 1, color: '#58a6ff' }
            ]
          }
        }
      }
    ]
  }
})

/** 车辆类型分布：后端给的是 Map<类型, 数量>，转成条形数据。 */
const typeRows = computed(() =>
  Object.entries(overview.value?.vehicleByType ?? {}).map(([type, count]) => ({ type, count }))
)

/* ------------------------------ 生命周期 ------------------------------ */

function startTimer() {
  stopTimer()
  if (autoRefresh.value) {
    timer = window.setInterval(loadAll, POLL_INTERVAL)
  }
}

function stopTimer() {
  if (timer) {
    window.clearInterval(timer)
    timer = undefined
  }
}

function toggleAutoRefresh(value: boolean) {
  autoRefresh.value = value
  startTimer()
}

onMounted(async () => {
  await loadAll()
  startTimer()
})

onBeforeUnmount(stopTimer)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>运营总览</h2>
        <p class="subtitle">
          统计口径按东八区自然日切分；严重故障定义为 severity ≥ 6。
        </p>
      </div>
      <div class="actions">
        <el-radio-group v-model="days" size="small" @change="loadAll">
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
          <el-radio-button :value="90">近 90 天</el-radio-button>
        </el-radio-group>
        <el-switch
          :model-value="autoRefresh"
          size="small"
          active-text="自动刷新"
          @update:model-value="toggleAutoRefresh"
        />
        <el-button size="small" :loading="loading" @click="loadAll">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
      </div>
    </div>

    <div class="stat-grid">
      <StatCard
        label="车辆总数"
        :value="formatNumber(overview?.vehicleTotal ?? 0)"
        unit="台"
        icon="Van"
        hint="已接入平台的车辆"
      />
      <StatCard
        label="在线车辆"
        :value="formatNumber(overview?.vehicleOnline ?? 0)"
        unit="台"
        :tone="onlineRateTone"
        icon="Connection"
        :hint="`在线率 ${overview?.vehicleOnlineRate ?? 0}% · 离线 ${overview?.vehicleOffline ?? 0} 台`"
      />
      <StatCard
        label="今日事故"
        :value="formatNumber(overview?.accidentToday ?? 0)"
        unit="起"
        :tone="(overview?.accidentToday ?? 0) > 0 ? 'warn' : 'ok'"
        icon="Warning"
        :hint="`昨日 ${overview?.accidentYesterday ?? 0} 起`"
      />
      <StatCard
        label="待取证事故"
        :value="formatNumber(overview?.accidentPendingMedia ?? 0)"
        unit="起"
        :tone="pendingTone"
        icon="VideoCamera"
        hint="含已请求/已受理/待上传"
      />
      <StatCard
        label="今日故障"
        :value="formatNumber(overview?.faultToday ?? 0)"
        unit="条"
        icon="Tools"
        :hint="`其中严重 ${overview?.faultSevereToday ?? 0} 条`"
      />
      <StatCard
        label="进行中任务"
        :value="formatNumber(overview?.taskRunning ?? 0)"
        unit="个"
        icon="Guide"
        :hint="`今日下发 ${overview?.taskToday ?? 0} 个`"
      />
      <StatCard
        label="今日远驾"
        :value="formatNumber(overview?.remoteDrivingToday ?? 0)"
        unit="次"
        :tone="(overview?.remoteDrivingActive ?? 0) > 0 ? 'warn' : 'default'"
        icon="Headset"
        :hint="`进行中 ${overview?.remoteDrivingActive ?? 0} 次`"
      />
      <StatCard
        label="地图阻断"
        :value="formatNumber(overview?.mapBarrierToday ?? 0)"
        unit="处"
        icon="LocationInformation"
        hint="今日生效的阻断设施"
      />
    </div>

    <div class="chart-grid-2">
      <div class="panel">
        <h3 class="panel-title">事故 / 故障趋势</h3>
        <EChart :option="trendOption" height="264px" :loading="loading" />
      </div>
      <div class="panel">
        <h3 class="panel-title">故障分类分布</h3>
        <EChart :option="categoryOption" height="264px" :loading="loading" />
      </div>
    </div>

    <div class="chart-grid-2">
      <div class="panel">
        <h3 class="panel-title">故障高发车辆 TOP 10</h3>
        <EChart
          :option="vehicleOption"
          :height="`${Math.max(200, (faultVehicles.length || 1) * 26 + 40)}px`"
          :loading="loading"
          empty-text="所选周期内没有故障记录"
        />
      </div>

      <div class="panel">
        <h3 class="panel-title">故障码 TOP 10</h3>
        <el-table :data="topCodes" size="small" height="264" empty-text="所选周期内没有故障记录">
          <el-table-column prop="errorCode" label="故障码" width="110" />
          <el-table-column label="故障名称" min-width="150">
            <template #default="{ row }">{{ row.name || '—' }}</template>
          </el-table-column>
          <el-table-column label="分类" width="120">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ row.categoryLabel || row.category || '—' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="count" label="次数" width="82" align="right" />
        </el-table>
      </div>
    </div>

    <div class="panel">
      <h3 class="panel-title">链路健康</h3>
      <div class="health-grid">
        <StatCard
          label="入站队列"
          :value="formatNumber(overview?.mqttInboundQueue ?? 0)"
          :tone="queueTone"
          hint="持续增长说明消费跟不上上报"
        />
        <StatCard
          label="入站丢弃"
          :value="formatNumber(overview?.mqttInboundDropped ?? 0)"
          :tone="(overview?.mqttInboundDropped ?? 0) > 0 ? 'danger' : 'ok'"
          hint="队列满导致的丢弃总数"
        />
        <StatCard
          label="离线补传队列"
          :value="formatNumber(overview?.mqttOfflineQueue ?? 0)"
          hint="Broker 断连期间待重发的下行指令"
        />
        <StatCard
          label="待 ACK"
          :value="formatNumber(overview?.mqttPendingAck ?? 0)"
          :tone="(overview?.mqttPendingAck ?? 0) > 50 ? 'warn' : 'default'"
          hint="已发送但未收到对端确认"
        />
        <StatCard
          label="近 24h 报文"
          :value="formatNumber(overview?.mqttRecords24h ?? 0)"
          hint="留痕条数（受采样策略影响）"
        />
        <StatCard
          label="数据生成时间"
          :value="formatTime(overview?.generatedAt)"
          hint="后端总览缓存 TTL 5 秒"
        />
      </div>

      <el-descriptions v-if="typeRows.length" :column="4" border size="small" class="type-table">
        <el-descriptions-item v-for="row in typeRows" :key="row.type" :label="row.type">
          {{ row.count }} 台
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <p class="muted footer-note">
      本页最近一次加载：{{ formatTime(lastLoadedAt) }}
      <template v-if="autoRefresh"> · 每 {{ POLL_INTERVAL / 1000 }} 秒自动刷新</template>
    </p>
  </div>
</template>

<style scoped>
.actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.health-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(158px, 1fr));
  gap: 12px;
}

.type-table {
  margin-top: 14px;
}

.footer-note {
  margin: 14px 2px 0;
  font-size: 11.5px;
}
</style>
