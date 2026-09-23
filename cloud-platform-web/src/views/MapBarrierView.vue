<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { mapBarrierApi } from '@/api'
import type { BarrierSummary, BarrierView } from '@/api/types'
import EChart from '@/components/EChart.vue'
import StatCard from '@/components/StatCard.vue'
import { formatDate, formatNumber } from '@/utils/format'

/**
 * 地图阻断设施（监管平台下发的交通管控区）。
 *
 * ============================ 数据来源与「为什么要有同步按钮」 ============================
 * 阻断设施有两条获取路径，互为补充：
 *   1. **推送**：监管平台通过 `mapehn/down` 主动下发（实时，但依赖对端）；
 *   2. **拉取**：平台每天 00:05 定时调用监管平台接口兜底（推送丢了也能补齐当天数据）。
 * 推送是「尽力而为」的，丢一条不会重发。所以运维发现「地图上少了一块管控区」时，
 * 需要能立即手动拉一次，而不是干等第二天 —— 这就是页面上的「立即同步」按钮。
 *
 * 幂等键是 `barrierId + effectiveDate`，两条路的数据不会重复入库，
 * 因此手工同步是**安全操作**，可以放心连点。
 *
 * 图形绘制沿用轨迹回放的方案：本地平面等比例投影 + 不接第三方地图底图。
 * 阻断区域本身就是闭合多边形，叠加底图只会引入底图合规与加载失败两类额外风险。
 */

const summary = ref<BarrierSummary | null>(null)
const todayList = ref<BarrierView[]>([])
const listLoading = ref(false)
const pullLoading = ref(false)
const areaCode = ref('')

/**
 * 回看日期：空字符串表示看今日（走 page/today 接口，命中后端当日缓存）。
 *
 * 用 `''` 而不是 `null` 表示「无日期」：Element Plus 的 DatePicker 把 `modelValue`
 * 声明为 `string | number | Date`（不含 null），传 null 在类型检查里就过不去。
 */
const viewDate = ref('')
const isHistory = computed(() => viewDate.value !== '')

/** 分页列表（与图形是一份数据的两个视图，因此共用同一份 rows）。 */
const pager = reactive({ page: 1, size: 50, total: 0 })
const rows = ref<BarrierView[]>([])

/** 当前选中的设施（点图形或点列表行）。 */
const selected = ref<BarrierView | null>(null)

/**
 * 阻断设施类型配色。
 *
 * 按「对通行的阻断强度」而不是随机配色：施工占道、道路封闭这类需要立刻绕行的用暖色/红色，
 * 限速类用蓝色。运维扫视地图时靠颜色分级判断影响面，比读文字快得多。
 */
const TYPE_COLORS: Record<string, string> = {
  施工: '#d29922',
  道路封闭: '#f85149',
  交通管制: '#e06c3a',
  限速: '#2f81f7',
  临时停车: '#8957e5'
}

function barrierColor(type?: string, index = 0): string {
  if (type && TYPE_COLORS[type]) return TYPE_COLORS[type]
  const fallback = ['#2f81f7', '#d29922', '#f85149', '#3fb950', '#8957e5', '#8b9bb0']
  return fallback[index % fallback.length]
}

/** 多边形坐标环已由后端解析成 `[[lng, lat], ...]`，前端不再解析原始字符串。 */
function ringOf(barrier: BarrierView): number[][] {
  const ring = barrier.barrierPolygon
  if (!Array.isArray(ring)) return []
  return ring
    .filter((point) => Array.isArray(point) && point.length >= 2)
    .map((point) => [Number(point[0]), Number(point[1])])
    .filter(([lng, lat]) => Number.isFinite(lng) && Number.isFinite(lat))
}

const drawableBarriers = computed(() => rows.value.filter((barrier) => ringOf(barrier).length >= 3))

/**
 * 阻断设施平面图。
 *
 * 与轨迹回放同一套等比例做法：以所有多边形的共同外接框为中心，
 * 把数据空间补成正方形并做经度余弦修正，容器再用 `aspect-ratio: 1/1`，
 * 保证 1 米横纵像素相同 —— 否则阻断区域的形状（尤其是长条形路段）会被扭曲，
 * 导致「看着像占了整条路」但实际只是局部。
 */
const barrierOption = computed(() => {
  const drawables = drawableBarriers.value
  if (drawables.length === 0) {
    return { series: [] }
  }

  let minLng = Number.POSITIVE_INFINITY
  let maxLng = Number.NEGATIVE_INFINITY
  let minLat = Number.POSITIVE_INFINITY
  let maxLat = Number.NEGATIVE_INFINITY

  drawables.forEach((barrier) => {
    ringOf(barrier).forEach(([lng, lat]) => {
      if (lng < minLng) minLng = lng
      if (lng > maxLng) maxLng = lng
      if (lat < minLat) minLat = lat
      if (lat > maxLat) maxLat = lat
    })
  })

  if (!Number.isFinite(minLng) || !Number.isFinite(minLat)) {
    return { series: [] }
  }

  const centerLng = (minLng + maxLng) / 2
  const centerLat = (minLat + maxLat) / 2
  const lngScale = Math.cos((centerLat * Math.PI) / 180) || 1
  const spanX = (maxLng - minLng) * lngScale
  const spanY = maxLat - minLat
  // 最小边长兜底：单点/极小区域时也不会把坐标轴压成 0 跨度（会导致渲染空白）
  const half = Math.max((Math.max(spanX, spanY) / 2) * 1.2, 0.001)

  const series = drawables.map((barrier, index) => {
    const ring = ringOf(barrier)
    // 闭合环：ECharts 的 line 不会自动闭合多边形，手动把首点补到末尾
    const closed = [...ring, ring[0]]
    const color = barrierColor(barrier.barrierType, index)
    return {
      name: barrier.barrierName || barrier.barrierId,
      type: 'line' as const,
      showSymbol: false,
      lineStyle: { width: 2, color },
      itemStyle: { color },
      areaStyle: { color, opacity: 0.22 },
      data: closed,
      // 选中项加粗描边，让「点列表→图上高亮」这件事可感知
      emphasis: { focus: 'series', lineStyle: { width: 4 } },
      z: selected.value?.barrierId === barrier.barrierId ? 10 : 1
    }
  })

  return {
    grid: { left: 8, right: 8, top: 8, bottom: 8 },
    tooltip: {
      trigger: 'item',
      formatter: (params: { seriesName?: string }) => {
        const hit = drawables.find(
          (barrier) => (barrier.barrierName || barrier.barrierId) === params.seriesName
        )
        if (!hit) return params.seriesName ?? ''
        const limit = hit.speedLimit == null ? '' : `<br/>限速 ${hit.speedLimit} km/h`
        const ranges = (hit.timeRange ?? []).map((range) => range.join('~')).join('、')
        return (
          `<b>${hit.barrierName || hit.barrierId}</b><br/>` +
          `类型：${hit.barrierTypeLabel || hit.barrierType || '—'}<br/>` +
          `区域：${hit.areaCode || '—'}<br/>` +
          `生效时段：${ranges || '全天'}` +
          limit
        )
      }
    },
    xAxis: {
      type: 'value',
      min: centerLng - half / lngScale,
      max: centerLng + half / lngScale,
      show: false
    },
    yAxis: { type: 'value', min: centerLat - half, max: centerLat + half, show: false },
    series
  }
})

function timeRangeText(barrier: BarrierView): string {
  const ranges = barrier.timeRange ?? []
  if (ranges.length === 0) return '全天'
  return ranges.map((range) => range.join('~')).join('、')
}

async function loadSummary() {
  try {
    summary.value = await mapBarrierApi.summary()
  } catch {
    summary.value = null
  }
}

async function loadList() {
  listLoading.value = true
  try {
    if (viewDate.value) {
      // 回看历史日期：走 by-date，接口按天全量返回（事故复盘时还原当时路况）
      const list = await mapBarrierApi.byDate(viewDate.value)
      rows.value = list ?? []
      pager.total = rows.value.length
      todayList.value = []
    } else {
      const result = await mapBarrierApi.page({ page: pager.page, size: pager.size })
      rows.value = result.list ?? []
      pager.total = result.total ?? 0
      // 今日全量用于图形（一次性取完，通常只有几十条）
      todayList.value = await mapBarrierApi.today()
    }
  } catch {
    rows.value = []
    pager.total = 0
  } finally {
    listLoading.value = false
  }
}

/** 切回今日。 */
function backToToday() {
  viewDate.value = ''
  pager.page = 1
  void Promise.all([loadSummary(), loadList()])
}

async function pullNow() {
  pullLoading.value = true
  try {
    const result = await mapBarrierApi.pull(areaCode.value.trim() || undefined)
    ElMessage.success(
      `已从监管平台拉取：本次获取 ${formatNumber(result.fetched ?? 0)} 条` +
        (result.fetched ? '（幂等键为 barrierId + 生效日期，重复拉取不会产生脏数据）' : '（对端当前无新增数据）')
    )
    await backToToday()
  } catch {
    // 对端不可达等错误已由 http 层提示
  } finally {
    pullLoading.value = false
  }
}

function selectBarrier(row: BarrierView) {
  selected.value = selected.value?.barrierId === row.barrierId ? null : row
}

onMounted(() => {
  void loadSummary()
  void loadList()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>地图阻断</h2>
        <p class="subtitle">
          数据来自监管平台推送（mapehn/down）+ 每日兜底拉取；幂等键为 barrierId + 生效日期，手工同步可放心重复执行。
        </p>
      </div>
      <div class="header-actions">
        <el-date-picker
          v-model="viewDate"
          type="date"
          placeholder="回看某日路况"
          value-format="YYYY-MM-DD"
          size="small"
          style="width: 168px"
          @change="loadList"
        />
        <el-button v-if="isHistory" size="small" @click="backToToday">返回今日</el-button>
        <el-button size="small" :loading="listLoading" @click="loadList">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
      </div>
    </div>

    <div class="stat-grid">
      <StatCard
        label="今日阻断设施"
        :value="formatNumber(summary?.count ?? todayList.length)"
        unit="处"
        tone="warn"
        :hint="summary?.date ? `统计日期 ${formatDate(summary.date)}` : '来自监管平台推送 + 定时拉取'"
        icon="LocationInformation"
      />
      <StatCard
        label="当前视图"
        :value="isHistory ? '历史回看' : '今日实时'"
        :tone="isHistory ? 'default' : 'ok'"
        :hint="isHistory ? `日期 ${viewDate}（事故复盘用）` : '显示当日全量数据'"
        icon="Calendar"
      />
      <StatCard
        label="可绘制区域"
        :value="formatNumber(drawableBarriers.length)"
        unit="处"
        :hint="`共 ${rows.length} 条记录，缺少多边形坐标的无法绘制`"
        icon="Picture"
      />
      <StatCard
        label="已回执监管"
        :value="formatNumber(rows.filter((item) => item.acked).length)"
        unit="处"
        :tone="rows.some((item) => !item.acked) ? 'warn' : 'ok'"
        hint="未回执的设施监管侧会认为平台没收到"
        icon="Select"
      />
    </div>

    <div class="panel">
      <h3 class="panel-title">立即同步</h3>
      <p class="muted note">
        监管平台的推送是「尽力而为」，丢一条不会重发。发现地图上少了管控区时点这里立即拉一次，
        不必等到次日 00:05 的定时任务。可指定区域编码只拉某一片区。
      </p>
      <el-form :inline="true" @submit.prevent="pullNow">
        <el-form-item label="区域编码">
          <el-input
            v-model="areaCode"
            placeholder="留空 = 全量拉取"
            clearable
            style="width: 200px"
            @keyup.enter="pullNow"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="pullLoading" @click="pullNow">
            <el-icon><Download /></el-icon> 立即同步
          </el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="barrier-layout">
      <div class="panel">
        <h3 class="panel-title">阻断区域平面图（等比例）</h3>
        <p class="muted note">
          不接第三方地图底图：阻断区域本身即闭合多边形，叠加底图只会引入合规与加载失败两类风险。
          横纵比例 1:1 且经度已做纬度余弦修正，长条形路段的形状不会被扭曲。
        </p>
        <EChart
          :option="barrierOption"
          aspect="1 / 1"
          :loading="listLoading"
          empty-text="当日没有可绘制的阻断区域（缺少多边形坐标）"
        />
        <div v-if="selected" class="selected-card">
          <div class="selected-head">
            <span class="selected-name">{{ selected.barrierName || selected.barrierId }}</span>
            <el-tag size="small" effect="dark">{{ selected.barrierTypeLabel || selected.barrierType || '未分类' }}</el-tag>
          </div>
          <el-descriptions :column="2" size="small" border>
            <el-descriptions-item label="设施编号">
              <span class="mono">{{ selected.barrierId }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="区域编码">{{ selected.areaCode || '—' }}</el-descriptions-item>
            <el-descriptions-item label="生效时段">{{ timeRangeText(selected) }}</el-descriptions-item>
            <el-descriptions-item label="限速">
              {{ selected.speedLimit == null ? '—' : `${selected.speedLimit} km/h` }}
            </el-descriptions-item>
            <el-descriptions-item label="数据来源">{{ selected.source || '—' }}</el-descriptions-item>
            <el-descriptions-item label="回执状态">
              <el-tag size="small" :type="selected.acked ? 'success' : 'warning'" effect="plain">
                {{ selected.acked ? '已回执' : '未回执' }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </div>

      <div class="panel">
        <h3 class="panel-title">
          设施清单（{{ formatNumber(pager.total) }}）
          <el-tag v-if="isHistory" size="small" type="info" effect="plain">{{ viewDate }} 回看</el-tag>
        </h3>
        <el-table
          v-loading="listLoading"
          :data="rows"
          size="small"
          height="600"
          highlight-current-row
          empty-text="当前视图没有阻断设施"
          @row-click="selectBarrier"
        >
          <el-table-column label="名称 / 编号" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <div>{{ row.barrierName || '未命名' }}</div>
              <div class="muted tiny mono">{{ row.barrierId }}</div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="120">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">
                {{ row.barrierTypeLabel || row.barrierType || '未分类' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="生效时段" width="160">
            <template #default="{ row }">{{ timeRangeText(row) }}</template>
          </el-table-column>
          <el-table-column label="限速" width="90" align="right">
            <template #default="{ row }">
              {{ row.speedLimit == null ? '—' : `${row.speedLimit}` }}
            </template>
          </el-table-column>
          <el-table-column label="区域" width="110" show-overflow-tooltip>
            <template #default="{ row }">{{ row.areaCode || '—' }}</template>
          </el-table-column>
          <el-table-column label="回执" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.acked ? 'success' : 'warning'" effect="plain">
                {{ row.acked ? '已回执' : '未回执' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          v-if="!isHistory"
          v-model:current-page="pager.page"
          v-model:page-size="pager.size"
          class="pager"
          small
          :total="pager.total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="loadList"
          @size-change="loadList"
        />
      </div>
    </div>
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

.barrier-layout {
  display: grid;
  grid-template-columns: minmax(360px, 1fr) minmax(340px, 1fr);
  gap: 16px;
  margin-top: 16px;
}

@media (max-width: 1200px) {
  .barrier-layout {
    grid-template-columns: 1fr;
  }
}

.selected-card {
  margin-top: 14px;
}

.selected-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.selected-name {
  font-weight: 600;
  font-size: 13.5px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.tiny {
  font-size: 11px;
  line-height: 1.4;
}

.barrier-layout :deep(.el-table__row) {
  cursor: pointer;
}
</style>
