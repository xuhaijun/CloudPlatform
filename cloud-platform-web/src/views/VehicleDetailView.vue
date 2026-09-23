<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { vehicleApi } from '@/api'
import type {
  TrackResult,
  Vehicle,
  VehicleDetail,
  VehiclePosition,
  VehicleState,
  VehicleStaticParam
} from '@/api/types'
import EChart from '@/components/EChart.vue'
import StatCard from '@/components/StatCard.vue'
import {
  formatDecimal,
  formatDuration,
  formatTime,
  formatRelative
} from '@/utils/format'

/**
 * 车辆详情与轨迹回放。
 *
 * ============================ 轨迹回放的关键取舍 ============================
 * **不接入地图 SDK，而是把经纬度投影到本地平面做等比例绘制。** 原因：
 *   1. 平台侧坐标是 GCJ-02，接入第三方底图涉及地图服务合规与密钥管理，
 *      属于部署方的选择，不该硬编码进前端；
 *   2. 轨迹回放的目的是「看路线形状、看速度分布、看拐点」，这些用平面投影
 *      完全够用，而且**没有底图加载失败导致整块画布空白**的风险；
 *   3. 后端已经做了 Douglas-Peucker 抽稀，前端只需忠实呈现并**显示抽稀前后点数**。
 *
 * 等比例的实现：把 X（东西）/Y（南北）都换算成米，取两者较大跨度把数据空间
 * **补成正方形**，容器再用 `aspect-ratio: 1 / 1` 保证像素也是正方形 ——
 * 两者叠加后 1 米在横向与纵向占的像素完全相同，路线不会被拉扁。
 */
const route = useRoute()
const router = useRouter()
const vin = computed(() => String(route.params.vin ?? ''))

const detail = ref<VehicleDetail | null>(null)
const state = ref<VehicleState | null>(null)
const position = ref<VehiclePosition | null>(null)
const staticParams = ref<VehicleStaticParam[]>([])

const loadingDetail = ref(false)
const loadingTrack = ref(false)
const track = ref<TrackResult | null>(null)

/** 默认查询最近 1 小时；1Hz 采样下约 3600 个原始点，抽稀后通常 < 500 个。 */
const range = ref<[number, number]>([Date.now() - 3600_000, Date.now()])

/* ------------------------------ 数据加载 ------------------------------ */

async function loadDetail() {
  loadingDetail.value = true
  try {
    const [d, s, p, sp] = await Promise.all([
      vehicleApi.detail(vin.value),
      vehicleApi.state(vin.value),
      vehicleApi.position(vin.value),
      vehicleApi.staticParams(vin.value, { page: 1, size: 20 })
    ])
    detail.value = d
    state.value = s
    position.value = p
    staticParams.value = sp.list ?? []
  } catch {
    /* http 层已提示 */
  } finally {
    loadingDetail.value = false
  }
}

async function loadTrack() {
  if (!range.value || range.value.length !== 2) {
    ElMessage.warning('请先选择时间范围')
    return
  }
  const [startTime, endTime] = range.value
  if (startTime >= endTime) {
    ElMessage.warning('开始时间必须早于结束时间')
    return
  }

  loadingTrack.value = true
  try {
    track.value = await vehicleApi.track(vin.value, { startTime, endTime, maxPoints: 3000 })
    if (!track.value?.points?.length) {
      ElMessage.info('该时间段内没有轨迹数据（车辆可能未上报位置）')
    }
  } catch {
    track.value = null
  } finally {
    loadingTrack.value = false
  }
}

function applyQuickRange(hours: number) {
  const end = Date.now()
  range.value = [end - hours * 3600_000, end]
  void loadTrack()
}

/* ------------------------------ 轨迹图表 ------------------------------ */

/** 每度经纬度对应的米数（在纬度 30° 附近，经度 1° ≈ 96 km，纬度 1° ≈ 111 km）。 */
const METERS_PER_DEGREE_LAT = 110_540
const METERS_PER_DEGREE_LON = 111_320

const trackStats = computed(() => {
  const points = track.value?.points ?? []
  const valid = points.filter((p) => p.longitude !== null && p.latitude !== null)
  if (!valid.length) return null

  const speeds = valid.map((p) => p.speed ?? 0)
  const maxSpeed = Math.max(...speeds)
  const avgSpeed = speeds.reduce((a, b) => a + b, 0) / speeds.length
  const durationSeconds = (valid[valid.length - 1].ts - valid[0].ts) / 1000

  return {
    maxSpeed,
    avgSpeed,
    durationSeconds,
    /** 自动驾驶/人工/接管 的采样点分布 */
    modeCounts: valid.reduce<Record<string, number>>((acc, p) => {
      const key = p.drivingMode === 0 ? '自动驾驶' : p.drivingMode === 1 ? '人工驾驶' : p.drivingMode === 2 ? '远程接管' : '未知'
      acc[key] = (acc[key] ?? 0) + 1
      return acc
    }, {})
  }
})

const trackOption = computed(() => {
  const points = (track.value?.points ?? []).filter(
    (p) => p.longitude !== null && p.latitude !== null
  )
  if (!points.length) return { series: [] }

  const lon0 = points[0].longitude as number
  const lat0 = points[0].latitude as number
  const cosLat = Math.cos((lat0 * Math.PI) / 180)

  const projected = points.map((p) => ({
    x: ((p.longitude as number) - lon0) * METERS_PER_DEGREE_LON * cosLat,
    y: ((p.latitude as number) - lat0) * METERS_PER_DEGREE_LAT,
    ts: p.ts,
    speed: p.speed,
    mode: p.drivingMode
  }))

  const xs = projected.map((p) => p.x)
  const ys = projected.map((p) => p.y)
  const minX = Math.min(...xs)
  const maxX = Math.max(...xs)
  const minY = Math.min(...ys)
  const maxY = Math.max(...ys)

  // 数据空间补成正方形（留 10% 边距），配合容器 1:1 实现等比例
  const span = Math.max(maxX - minX, maxY - minY, 20) * 1.1
  const centerX = (minX + maxX) / 2
  const centerY = (minY + maxY) / 2

  return {
    tooltip: {
      trigger: 'item',
      formatter: (params: { data: { ts: number; speed: number | null; mode: number | null } }) => {
        const { ts, speed, mode } = params.data
        const modeLabel = mode === 0 ? '自动驾驶' : mode === 1 ? '人工驾驶' : mode === 2 ? '远程接管' : '未知'
        return `${formatTime(ts)}<br/>速度 ${formatDecimal(speed, 1)} km/h<br/>模式 ${modeLabel}`
      }
    },
    grid: { left: 62, right: 22, top: 24, bottom: 46 },
    xAxis: {
      type: 'value',
      name: '东西向（米）',
      nameLocation: 'middle',
      nameGap: 28,
      nameTextStyle: { color: '#8b9bb0', fontSize: 11 },
      min: centerX - span / 2,
      max: centerX + span / 2,
      splitLine: { lineStyle: { color: '#1e2836' } },
      axisLine: { lineStyle: { color: '#263140' } }
    },
    yAxis: {
      type: 'value',
      name: '南北向（米）',
      nameTextStyle: { color: '#8b9bb0', fontSize: 11 },
      min: centerY - span / 2,
      max: centerY + span / 2,
      splitLine: { lineStyle: { color: '#1e2836' } },
      axisLine: { lineStyle: { color: '#263140' } }
    },
    series: [
      {
        type: 'line',
        showSymbol: false,
        smooth: false,
        lineStyle: { width: 2.4, color: '#58a6ff' },
        // 起终点用不同标记，翻转的路线一眼能看出来
        markPoint: {
          symbolSize: 46,
          label: { fontSize: 10, color: '#fff' },
          data: [
            { name: '起点', coord: [projected[0].x, projected[0].y], itemStyle: { color: '#3fb950' } },
            {
              name: '终点',
              coord: [projected[projected.length - 1].x, projected[projected.length - 1].y],
              itemStyle: { color: '#f85149' }
            }
          ]
        },
        data: projected.map((p) => ({ value: [p.x, p.y], ts: p.ts, speed: p.speed, mode: p.mode }))
      }
    ]
  }
})

/** 抽稀率：让运维能区分「轨迹不对」是抽稀造成还是真的丢数据。 */
const simplifyRatio = computed(() => {
  if (!track.value || !track.value.rawCount) return '—'
  return `${track.value.returnedCount} / ${track.value.rawCount}（${
    Math.round(((track.value.rawCount - track.value.returnedCount) / track.value.rawCount) * 100)
  }% 已抽稀）`
})

/**
 * 车辆档案字段。
 *
 * 后端把档案嵌在 `vehicle` 下，这里统一摊平给模板用；返回 `Partial<Vehicle>`
 * 而不是 `Record<string, unknown>`，是为了让模板里的 `formatRelative(lastBaseAt)`
 * 这类调用拿到真实类型（`unknown` 会直接编译不过，逼着我们把类型补对）。
 */
const detailFields = computed<Partial<Vehicle>>(
  () => detail.value?.vehicle ?? ({} as Partial<Vehicle>)
)

onMounted(async () => {
  await loadDetail()
  await loadTrack()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>
          车辆详情
          <span class="vin mono">{{ vin }}</span>
        </h2>
        <p class="subtitle">
          轨迹由后端做 Douglas-Peucker 抽稀（容差见 dssad.api.track-simplify-tolerance）。
        </p>
      </div>
      <div class="header-actions">
        <el-button size="small" :loading="loadingDetail" @click="loadDetail">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
        <el-button size="small" @click="router.back()">
          <el-icon><Back /></el-icon> 返回
        </el-button>
      </div>
    </div>

    <div class="panel">
      <h3 class="panel-title">实时状态</h3>
      <div class="stat-grid">
        <StatCard
          label="在线状态"
          :value="detailFields.online ? '在线' : '离线'"
          :tone="detailFields.online ? 'ok' : 'danger'"
          :hint="`最后上报 ${formatRelative(detailFields.lastBaseAt)}`"
        />
        <StatCard
          label="车速"
          :value="formatDecimal(position?.speed, 1)"
          unit="km/h"
          hint="来自最新位置报"
        />
        <StatCard
          label="SOC"
          :value="formatDecimal(state?.soc, 1)"
          unit="%"
          hint="电量百分比"
        />
        <StatCard
          label="续航"
          :value="formatDecimal(state?.endurance, 1)"
          unit="km"
        />
        <StatCard
          label="总里程"
          :value="formatDecimal(state?.mileage, 1)"
          unit="km"
        />
        <StatCard
          label="温度"
          :value="formatDecimal(state?.temperatureCelsius ?? (state?.temperature ?? null) as number | null, 1)"
          unit="℃"
          hint="协议原始值带 +100 偏移，已换算"
        />
      </div>

      <div class="grid-2">
        <el-descriptions title="车辆档案" :column="2" border size="small">
          <el-descriptions-item label="VIN">{{ detailFields.vin ?? vin }}</el-descriptions-item>
          <el-descriptions-item label="车牌">{{ detailFields.plateNo ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="车辆类型">{{ detailFields.vehicleType ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="车型">{{ detailFields.model ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="设备序列号">{{ detailFields.deviceSn ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="通信方式">{{ detailFields.comType ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="硬件版本">{{ detailFields.hwVersion ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="自动驾驶版本">{{ detailFields.adVersion ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="定位状态">{{ state?.gnssStatus ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="充电状态">{{ state?.chargeState ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="当前经度">
            {{ formatDecimal(position?.longitude, 6) }}
          </el-descriptions-item>
          <el-descriptions-item label="当前纬度">
            {{ formatDecimal(position?.latitude, 6) }}
          </el-descriptions-item>
          <el-descriptions-item label="位置采样时间" :span="2">
            {{ formatTime(position?.ts) }}
          </el-descriptions-item>
        </el-descriptions>

        <el-descriptions title="运行状态（最新快照）" :column="2" border size="small">
          <el-descriptions-item label="整车总质量">
            {{ formatDecimal(state?.vehicleTotalWeight, 0) }} kg
          </el-descriptions-item>
          <el-descriptions-item label="电池容量">
            {{ formatDecimal(state?.batteryCapacity, 1) }} kWh
          </el-descriptions-item>
          <el-descriptions-item label="胎压" :span="2">
            <template v-if="Array.isArray(state?.tirePressure) && state.tirePressure.length">
              <el-tag
                v-for="(p, index) in state!.tirePressure as number[]"
                :key="index"
                size="small"
                effect="plain"
                class="tire-tag"
              >
                {{ formatDecimal(p, 1) }} kPa
              </el-tag>
            </template>
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item label="状态上报时间" :span="2">
            {{ formatTime(state?.timestamp ?? state?.reportedAt) }}
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </div>

    <div class="panel">
      <h3 class="panel-title">轨迹回放</h3>

      <div class="track-toolbar">
        <el-date-picker
          v-model="range"
          type="datetimerange"
          size="small"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          :default-time="[new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]"
        />
        <el-button size="small" type="primary" :loading="loadingTrack" @click="loadTrack">
          <el-icon><Search /></el-icon> 查询轨迹
        </el-button>
        <el-divider direction="vertical" />
        <el-button-group>
          <el-button size="small" @click="applyQuickRange(1)">近 1 小时</el-button>
          <el-button size="small" @click="applyQuickRange(6)">近 6 小时</el-button>
          <el-button size="small" @click="applyQuickRange(24)">近 24 小时</el-button>
        </el-button-group>
      </div>

      <el-alert
        v-if="track"
        type="info"
        :closable="false"
        show-icon
        class="track-meta"
      >
        <template #title>
          抽稀 {{ simplifyRatio }} · 容差 {{ track.tolerance }} 米 ·
          时间跨度 {{ formatDuration(trackStats?.durationSeconds) }} ·
          最高 {{ formatDecimal(trackStats?.maxSpeed, 1) }} km/h ·
          平均 {{ formatDecimal(trackStats?.avgSpeed, 1) }} km/h
        </template>
      </el-alert>

      <div class="track-layout">
        <EChart
          :option="trackOption"
          aspect="1 / 1"
          :loading="loadingTrack"
          empty-text="该时间段内没有轨迹数据"
        />
        <div class="track-side">
          <h4>驾驶模式分布</h4>
          <div v-if="trackStats" class="mode-list">
            <div v-for="(count, mode) in trackStats.modeCounts" :key="mode" class="mode-item">
              <span class="muted">{{ mode }}</span>
              <span class="mono">{{ count }} 点</span>
            </div>
          </div>
          <p v-else class="muted">—</p>

          <h4 class="mt">说明</h4>
          <ul class="notes">
            <li>坐标为 GCJ-02，按本地平面投影绘制，<b>等比例</b>（1 米横纵像素相同）。</li>
            <li>不加载第三方底图：避免地图密钥与合规依赖，路线形状与速度分布已足够复盘。</li>
            <li>若点数明显偏少，先看上方「抽稀」比例，再怀疑数据缺失。</li>
          </ul>
        </div>
      </div>
    </div>

    <div class="grid-2">
      <div class="panel">
        <h3 class="panel-title">准静态参数变更史</h3>
        <el-table :data="staticParams" size="small" height="240" empty-text="暂无参数变更记录">
          <el-table-column label="上报时间" width="170">
            <template #default="{ row }">{{ formatTime(row.reportedAt) }}</template>
          </el-table-column>
          <el-table-column prop="hwVersion" label="硬件版本" min-width="110" />
          <el-table-column prop="adVersion" label="AD 版本" min-width="110" />
          <el-table-column prop="comType" label="通信方式" width="100" />
          <el-table-column prop="timeSync" label="时间同步" width="110" />
        </el-table>
      </div>

      <div class="panel">
        <h3 class="panel-title">档案原始数据</h3>
        <pre class="raw mono">{{ JSON.stringify(detail, null, 2) }}</pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.vin {
  margin-left: 8px;
  font-size: 12.5px;
  font-weight: 400;
  color: var(--dssad-text-dim);
}

.header-actions {
  display: flex;
  gap: 8px;
}

.grid-2 {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(420px, 1fr));
  gap: 16px;
  margin-top: 16px;
}

.tire-tag {
  margin-right: 6px;
}

.track-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.track-meta {
  margin-bottom: 14px;
}

.track-layout {
  display: grid;
  grid-template-columns: minmax(320px, 1fr) 240px;
  gap: 18px;
  align-items: start;
}

@media (max-width: 1100px) {
  .track-layout {
    grid-template-columns: 1fr;
  }
}

.track-side h4 {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
}

.track-side h4.mt {
  margin-top: 18px;
}

.mode-item {
  display: flex;
  justify-content: space-between;
  padding: 5px 0;
  border-bottom: 1px dashed var(--dssad-border);
  font-size: 12.5px;
}

.notes {
  margin: 0;
  padding-left: 16px;
  color: var(--dssad-text-dim);
  font-size: 11.5px;
  line-height: 1.85;
}

.raw {
  max-height: 240px;
  overflow: auto;
  margin: 0;
  padding: 12px;
  background: var(--dssad-panel-2);
  border-radius: 8px;
  color: #a9b7c8;
  font-size: 11.5px;
  line-height: 1.6;
}
</style>
