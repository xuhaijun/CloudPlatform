<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { dictApi, eventApi } from '@/api'
import type {
  AccidentEvent,
  AccidentMediaStatus,
  DictEntry,
  FaultCodeEntry,
  FaultCodeStat,
  FaultDetail,
  FaultRecord,
  MediaAsset
} from '@/api/types'
import EChart from '@/components/EChart.vue'
import StatCard from '@/components/StatCard.vue'
import { formatBytes, formatNumber, formatRelative, formatTime, parseTime } from '@/utils/format'

/**
 * 事件中心：事故取证闭环 + 故障中心。
 *
 * ============================ 为什么事故要单独做「闭环」视图 ============================
 * 事故处理不是一个状态，而是一条**有时间压力的流水线**：
 *   车端上报事故 → 平台下发取证请求 → 车端应答 → 上传四方向视频 → 归档完成
 * 每一步都依赖对端配合，任何一步卡住都会导致「取证要素不齐」——
 * 而事故视频有严格时效（文档要求前 15s + 后 5s），拖过时限就永久丢失。
 *
 * 因此本页把「取证状态 + 距上报多久」放在最显眼的位置，
 * 并额外提供「待归档事故」清单（超时未收到取证的挂起事故），
 * 让运维能按「等待时长」排队催办，而不是等用户投诉才发现漏了。
 *
 * 故障中心是另一条线：故障只需落库统计，不存在取证时效，
 * 因此用「严重度 ≥6」做筛选口径，聚焦真正需要处理的严重故障。
 */

/**
 * 页签。
 *
 * 故意声明成宽泛的 `string` 而不是 `'accident' | 'fault'`：Element Plus 的 Tabs
 * 把 `modelValue` 声明为 `string | number`，用窄字面量联合类型会在
 * `v-model` 的写入方向被判定为「把 string 赋给窄类型」而报类型错误。
 */
const activeTab = ref('accident')

// ==================== 字典 ====================

const faultCodeDict = ref<Map<string, FaultCodeEntry>>(new Map())
const cameraDirections = ref<DictEntry[]>([])

// ==================== 事故 ====================

const accidentQuery = reactive({
  vin: '',
  mediaStatus: undefined as AccidentMediaStatus | undefined,
  hours: 168,
  page: 1,
  size: 20
})

const accidents = ref<AccidentEvent[]>([])
const accidentTotal = ref(0)
const accidentLoading = ref(false)
const pending = ref<AccidentEvent[]>([])
const pendingLoading = ref(false)

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailEvent = ref<AccidentEvent | null>(null)
const detailMedia = ref<MediaAsset[]>([])
const detailMediaComplete = ref(false)

// ==================== 故障 ====================

const faultQuery = reactive({
  vin: '',
  minSeverity: undefined as number | undefined,
  hours: 168,
  page: 1,
  size: 20
})

const faults = ref<FaultRecord[]>([])
const faultTotal = ref(0)
const faultLoading = ref(false)
const faultDetailVisible = ref(false)
const faultDetailLoading = ref(false)
const faultDetail = ref<FaultDetail | null>(null)

const topCodes = ref<FaultCodeStat[]>([])
const topCodeDays = ref(7)

// ==================== 派生数据 ====================

/**
 * 取证状态机的展示元数据。
 *
 * 顺序有业务含义：MEDIA_ACCEPTED 只在「车端已应答、文件还没到」时出现，
 * 是**最需要盯**的中间态（应答了但不传，说明车端存储或网络有问题）。
 * MEDIA_FAILED 不进主线，它是终态失败，必须人工介入。
 */
const MEDIA_FLOW: AccidentMediaStatus[] = ['REPORTED', 'MEDIA_REQUESTED', 'MEDIA_ACCEPTED', 'MEDIA_RECEIVED']
const MEDIA_FLOW_LABEL: Record<string, string> = {
  REPORTED: '已上报',
  MEDIA_REQUESTED: '已请求取证',
  MEDIA_ACCEPTED: '车端已应答',
  MEDIA_RECEIVED: '取证完成'
}

/** 表格里的状态标签元数据（含失败态）。 */
function mediaStatusMeta(status?: AccidentMediaStatus): { label: string; type: 'success' | 'warning' | 'danger' | 'info' | 'primary' } {
  switch (status) {
    case 'REPORTED':
      return { label: '已上报', type: 'info' }
    case 'MEDIA_REQUESTED':
      return { label: '已请求取证', type: 'primary' }
    case 'MEDIA_ACCEPTED':
      return { label: '车端已应答', type: 'warning' }
    case 'MEDIA_RECEIVED':
      return { label: '取证完成', type: 'success' }
    case 'MEDIA_FAILED':
      return { label: '取证失败', type: 'danger' }
    default:
      return { label: status ?? '未知', type: 'info' }
  }
}

/** 取证流程进度（0~4），失败记 0 以便与「刚上报」区分不了时靠标签兜底。 */
function mediaStep(status?: AccidentMediaStatus): number {
  const index = MEDIA_FLOW.indexOf(status as AccidentMediaStatus)
  return index < 0 ? 0 : index
}

/**
 * 事故上报距今超过 10 分钟仍未完成取证 → 需要催办（与后端 pending 的默认口径一致）。
 *
 * 用 `parseTime` 而不是 `new Date(value)`：后端时间字段是 13 位毫秒数或 `[年,月,日]`
 * 数组，直接扔给 Date 会得到 Invalid Date（见 utils/format.ts 顶部说明）。
 */
function isStuck(event: AccidentEvent): boolean {
  if (event.mediaStatus === 'MEDIA_RECEIVED') return false
  const occurred = parseTime(event.occurredAt)
  if (!occurred) return false
  return Date.now() - occurred.getTime() > 10 * 60 * 1000
}

/** 严重故障口径：1~10，≥6 视为严重（与后端「严重故障数」统计一致）。 */
const SEVERE_THRESHOLD = 6

function severityTagType(severity?: number | null) {
  if (severity === null || severity === undefined) return 'info'
  if (severity >= 8) return 'danger'
  if (severity >= SEVERE_THRESHOLD) return 'warning'
  return 'info'
}

function faultCodeName(code?: string): string {
  if (!code) return '—'
  const entry = faultCodeDict.value.get(code)
  return entry?.name ?? '未知故障码'
}

function faultCodeCategory(code?: string): string {
  if (!code) return ''
  return faultCodeDict.value.get(code)?.categoryLabel ?? ''
}

function directionLabel(value?: string): string {
  if (!value) return '—'
  return cameraDirections.value.find((item) => item.value === value)?.label ?? `方向 ${value}`
}

const topCodesOption = computed(() => {
  const rows = [...topCodes.value].sort((a, b) => b.count - a.count)
  return {
    grid: { left: 130, right: 44, top: 14, bottom: 14 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', splitLine: { lineStyle: { color: '#263140' } } },
    yAxis: {
      type: 'category',
      inverse: true,
      axisLine: { lineStyle: { color: '#263140' } },
      data: rows.map((row) => `${row.errorCode} ${row.name ?? ''}`)
    },
    series: [
      {
        type: 'bar',
        barWidth: 14,
        itemStyle: { color: '#f85149' },
        label: { show: true, position: 'right', color: '#d7e0ea' },
        data: rows.map((row) => row.count)
      }
    ]
  }
})

// ==================== 数据加载 ====================

async function loadDict() {
  try {
    const [codes, directions] = await Promise.all([dictApi.faultCodes(), dictApi.cameraDirections()])
    faultCodeDict.value = new Map(codes.map((item) => [item.code, item]))
    cameraDirections.value = directions
  } catch {
    // 字典失败只影响翻译显示，不影响主流程；保持空 Map 即可
  }
}

async function loadAccidents() {
  accidentLoading.value = true
  try {
    const result = await eventApi.accidents({
      vin: accidentQuery.vin.trim() || undefined,
      mediaStatus: accidentQuery.mediaStatus,
      hours: accidentQuery.hours,
      page: accidentQuery.page,
      size: accidentQuery.size
    })
    accidents.value = result.list ?? []
    accidentTotal.value = result.total ?? 0
  } catch {
    accidents.value = []
    accidentTotal.value = 0
  } finally {
    accidentLoading.value = false
  }
}

async function loadPending() {
  pendingLoading.value = true
  try {
    pending.value = await eventApi.pendingAccidents({ timeoutMinutes: 10, limit: 50 })
  } catch {
    pending.value = []
  } finally {
    pendingLoading.value = false
  }
}

async function loadFaults() {
  faultLoading.value = true
  try {
    const result = await eventApi.faults({
      vin: faultQuery.vin.trim() || undefined,
      minSeverity: faultQuery.minSeverity,
      hours: faultQuery.hours,
      page: faultQuery.page,
      size: faultQuery.size
    })
    faults.value = result.list ?? []
    faultTotal.value = result.total ?? 0
  } catch {
    faults.value = []
    faultTotal.value = 0
  } finally {
    faultLoading.value = false
  }
}

async function loadTopCodes() {
  try {
    topCodes.value = await eventApi.topFaultCodes(topCodeDays.value, 10)
  } catch {
    topCodes.value = []
  }
}

async function openAccidentDetail(row: AccidentEvent) {
  detailVisible.value = true
  detailLoading.value = true
  detailEvent.value = row
  detailMedia.value = []
  detailMediaComplete.value = false
  try {
    const detail = await eventApi.accidentDetail(row.eventId)
    detailEvent.value = detail.event ?? row
    detailMedia.value = detail.media ?? []
    detailMediaComplete.value = detail.mediaComplete ?? false
  } catch {
    // 详情失败时保留列表行数据，用户至少还能看到基本信息
  } finally {
    detailLoading.value = false
  }
}

async function openFaultDetail(row: FaultRecord) {
  faultDetailVisible.value = true
  faultDetailLoading.value = true
  faultDetail.value = null
  try {
    faultDetail.value = await eventApi.faultDetail(row.id)
  } catch {
    // 同上
  } finally {
    faultDetailLoading.value = false
  }
}

function resetAccidentQuery() {
  accidentQuery.vin = ''
  accidentQuery.mediaStatus = undefined
  accidentQuery.hours = 168
  accidentQuery.page = 1
  void loadAccidents()
}

function resetFaultQuery() {
  faultQuery.vin = ''
  faultQuery.minSeverity = undefined
  faultQuery.hours = 168
  faultQuery.page = 1
  void loadFaults()
}

/** 从待归档清单直接跳到对应事故详情，省掉「再去列表里搜一遍」的重复劳动。 */
function openPendingDetail(row: AccidentEvent) {
  void openAccidentDetail(row)
}

async function loadAll() {
  await Promise.all([loadAccidents(), loadPending(), loadFaults(), loadTopCodes()])
}

onMounted(async () => {
  await loadDict()
  await loadAll()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>事件中心</h2>
        <p class="subtitle">
          事故走「取证闭环」——每一步都有时效；故障走「统计口径」——严重度 ≥6 优先处理。
        </p>
      </div>
      <el-button size="small" :loading="accidentLoading || faultLoading" @click="loadAll">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
    </div>

    <!-- ========== 待归档事故（运维催办清单） ========== -->
    <div class="stat-grid">
      <StatCard
        label="待归档事故"
        :value="formatNumber(pending.length)"
        unit="起"
        :tone="pending.length > 0 ? 'danger' : 'ok'"
        hint="超 10 分钟未收到取证的挂起事故"
        icon="Timer"
      />
      <StatCard
        label="查询区间事故"
        :value="formatNumber(accidentTotal)"
        unit="起"
        hint="按当前筛选条件统计"
        icon="Warning"
      />
      <StatCard
        label="查询区间故障"
        :value="formatNumber(faultTotal)"
        unit="条"
        tone="warn"
        hint="含未达严重度的普通故障"
        icon="Bell"
      />
      <StatCard
        label="故障码种类"
        :value="formatNumber(topCodes.length)"
        unit="种"
        hint="近 7 天 Top 榜涉及的故障码数量"
        icon="Collection"
      />
    </div>

    <div v-if="pending.length" class="panel pending-panel">
      <h3 class="panel-title">
        待归档事故（{{ pending.length }}）
        <el-tag type="danger" size="small" effect="dark">需催办</el-tag>
      </h3>
      <p class="muted note">
        事故视频有严格时效（前 15s + 后 5s），拖过时限就<b>永久丢失</b>。
        按等待时长排序处理：下面这批事故已超过 10 分钟未拿到取证，
        若对端声称「已上传」，请到链路监控页按 msgId 追溯留痕自证。
      </p>
      <el-table v-loading="pendingLoading" :data="pending" size="small">
        <el-table-column prop="eventId" label="事故 ID" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.eventId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="vin" label="VIN" min-width="150" show-overflow-tooltip />
        <el-table-column label="上报时间" width="170">
          <template #default="{ row }">
            <div>{{ formatTime(row.occurredAt) }}</div>
            <div class="muted tiny text-danger">{{ formatRelative(row.occurredAt) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="取证状态" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="mediaStatusMeta(row.mediaStatus).type" effect="dark">
              {{ mediaStatusMeta(row.mediaStatus).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="已收/期望" width="100" align="center">
          <template #default="{ row }">
            <span :class="{ 'text-danger': (row.mediaCount ?? 0) < (row.mediaExpectedCount ?? 4) }">
              {{ row.mediaCount ?? 0 }} / {{ row.mediaExpectedCount ?? 4 }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="88">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openPendingDetail(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="panel tabs-panel">
      <el-tabs v-model="activeTab">
        <!-- ==================== 事故 ==================== -->
        <el-tab-pane label="事故中心" name="accident">
          <el-form :inline="true" class="filters" @submit.prevent="loadAccidents">
            <el-form-item label="VIN">
              <el-input
                v-model="accidentQuery.vin"
                placeholder="车辆 VIN"
                clearable
                style="width: 200px"
                @keyup.enter="loadAccidents"
              />
            </el-form-item>
            <el-form-item label="取证状态">
              <el-select v-model="accidentQuery.mediaStatus" placeholder="全部" clearable style="width: 160px">
                <el-option label="已上报" value="REPORTED" />
                <el-option label="已请求取证" value="MEDIA_REQUESTED" />
                <el-option label="车端已应答" value="MEDIA_ACCEPTED" />
                <el-option label="取证完成" value="MEDIA_RECEIVED" />
                <el-option label="取证失败" value="MEDIA_FAILED" />
              </el-select>
            </el-form-item>
            <el-form-item label="时间范围">
              <el-select v-model="accidentQuery.hours" style="width: 130px">
                <el-option label="近 24 小时" :value="24" />
                <el-option label="近 7 天" :value="168" />
                <el-option label="近 30 天" :value="720" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadAccidents">查询</el-button>
              <el-button @click="resetAccidentQuery">重置</el-button>
            </el-form-item>
          </el-form>

          <el-table
            v-loading="accidentLoading"
            :data="accidents"
            size="small"
            empty-text="查询区间内没有事故记录"
          >
            <el-table-column prop="eventId" label="事故 ID" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="mono">{{ row.eventId }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="vin" label="VIN" min-width="150" show-overflow-tooltip />
            <el-table-column label="发生时间" width="170">
              <template #default="{ row }">
                <div>{{ formatTime(row.occurredAt) }}</div>
                <div class="muted tiny">{{ formatRelative(row.occurredAt) }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="accidentDesc" label="事故描述" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">{{ row.accidentDesc || '—' }}</template>
            </el-table-column>
            <el-table-column label="位置" width="180">
              <template #default="{ row }">
                <span v-if="row.latitude === null || row.latitude === undefined" class="muted">—</span>
                <span v-else class="mono">{{ row.longitude?.toFixed(6) }}, {{ row.latitude?.toFixed(6) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="取证状态" width="130">
              <template #default="{ row }">
                <el-tag size="small" :type="mediaStatusMeta(row.mediaStatus).type" effect="dark">
                  {{ mediaStatusMeta(row.mediaStatus).label }}
                </el-tag>
                <div v-if="isStuck(row)" class="muted tiny text-warn">已超时</div>
              </template>
            </el-table-column>
            <el-table-column label="视频" width="90" align="center">
              <template #default="{ row }">
                <span :class="{ 'text-warn': (row.mediaCount ?? 0) < (row.mediaExpectedCount ?? 4) }">
                  {{ row.mediaCount ?? 0 }} / {{ row.mediaExpectedCount ?? 4 }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="88" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openAccidentDetail(row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-model:current-page="accidentQuery.page"
            v-model:page-size="accidentQuery.size"
            class="pager"
            :total="accidentTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next, jumper"
            @current-change="loadAccidents"
            @size-change="loadAccidents"
          />
        </el-tab-pane>

        <!-- ==================== 故障 ==================== -->
        <el-tab-pane label="故障中心" name="fault">
          <el-form :inline="true" class="filters" @submit.prevent="loadFaults">
            <el-form-item label="VIN">
              <el-input
                v-model="faultQuery.vin"
                placeholder="车辆 VIN"
                clearable
                style="width: 200px"
                @keyup.enter="loadFaults"
              />
            </el-form-item>
            <el-form-item label="最低严重度">
              <el-select v-model="faultQuery.minSeverity" placeholder="全部" clearable style="width: 150px">
                <el-option label="≥6 严重" :value="SEVERE_THRESHOLD" />
                <el-option label="≥8 高危" :value="8" />
                <el-option label="≥1 全部" :value="1" />
              </el-select>
            </el-form-item>
            <el-form-item label="时间范围">
              <el-select v-model="faultQuery.hours" style="width: 130px">
                <el-option label="近 24 小时" :value="24" />
                <el-option label="近 7 天" :value="168" />
                <el-option label="近 30 天" :value="720" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadFaults">查询</el-button>
              <el-button @click="resetFaultQuery">重置</el-button>
            </el-form-item>
          </el-form>

          <el-table v-loading="faultLoading" :data="faults" size="small" empty-text="查询区间内没有故障记录">
            <el-table-column prop="id" label="记录 ID" width="90" />
            <el-table-column prop="vin" label="VIN" min-width="150" show-overflow-tooltip />
            <el-table-column label="上报时间" width="170">
              <template #default="{ row }">
                <div>{{ formatTime(row.reportedAt) }}</div>
                <div class="muted tiny">{{ formatRelative(row.reportedAt) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="故障项数" width="96" align="right">
              <template #default="{ row }">{{ formatNumber(row.errorNum ?? 0) }}</template>
            </el-table-column>
            <el-table-column label="最高严重度" width="120" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="severityTagType(row.maxSeverity)" effect="dark">
                  {{ row.maxSeverity ?? '—' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="位置" width="180">
              <template #default="{ row }">
                <span v-if="row.latitude === null || row.latitude === undefined" class="muted">—</span>
                <span v-else class="mono">{{ row.longitude?.toFixed(6) }}, {{ row.latitude?.toFixed(6) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="88" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openFaultDetail(row)">明细</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-model:current-page="faultQuery.page"
            v-model:page-size="faultQuery.size"
            class="pager"
            :total="faultTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next, jumper"
            @current-change="loadFaults"
            @size-change="loadFaults"
          />

          <h3 class="panel-title chart-title">故障码 Top 10</h3>
          <el-select v-model="topCodeDays" size="small" style="width: 120px; margin-bottom: 10px" @change="loadTopCodes">
            <el-option label="近 7 天" :value="7" />
            <el-option label="近 30 天" :value="30" />
            <el-option label="近 90 天" :value="90" />
          </el-select>
          <EChart :option="topCodesOption" height="300px" empty-text="区间内没有故障记录" />
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- ==================== 事故详情 ==================== -->
    <el-dialog v-model="detailVisible" title="事故详情与取证闭环" width="900px" top="6vh">
      <div v-loading="detailLoading">
        <template v-if="detailEvent">
          <el-alert
            v-if="!detailMediaComplete"
            type="warning"
            :closable="false"
            show-icon
            title="取证要素不齐"
            description="文档 3.3 要求事故取证覆盖四方向视频（前/右/后/左）。缺失的方向无法事后补齐，请核对下方清单并联系车端。"

            class="complete-alert"
          />
          <el-alert
            v-else
            type="success"
            :closable="false"
            show-icon
            title="取证要素齐备（四方向视频已归档）"
            class="complete-alert"
          />

          <h4 class="block-title">取证流程</h4>
          <el-steps
            :active="mediaStep(detailEvent.mediaStatus)"
            finish-status="success"
            align-center
            class="media-steps"
          >
            <el-step
              v-for="step in MEDIA_FLOW"
              :key="step"
              :title="MEDIA_FLOW_LABEL[step]"
              :status="detailEvent.mediaStatus === 'MEDIA_FAILED' && step === 'MEDIA_REQUESTED' ? 'error' : undefined"
            />
          </el-steps>
          <p class="muted note">
            当前状态：
            <el-tag size="small" :type="mediaStatusMeta(detailEvent.mediaStatus).type" effect="dark">
              {{ mediaStatusMeta(detailEvent.mediaStatus).label }}
            </el-tag>
            「车端已应答」是最需要盯的中间态 —— 应答了却不传文件，通常是车端存储或带宽问题。
          </p>

          <h4 class="block-title">事故信息</h4>
          <el-descriptions :column="2" size="small" border>
            <el-descriptions-item label="事故 ID">
              <span class="mono">{{ detailEvent.eventId }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="车辆 VIN">
              <span class="mono">{{ detailEvent.vin }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="发生时间">{{ formatTime(detailEvent.occurredAt) }}</el-descriptions-item>
            <el-descriptions-item label="上报消息编号">
              <span class="mono">{{ detailEvent.msgId || '—' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="位置">
              <span v-if="detailEvent.latitude === null || detailEvent.latitude === undefined" class="muted">—</span>
              <span v-else class="mono">
                {{ detailEvent.longitude?.toFixed(6) }}, {{ detailEvent.latitude?.toFixed(6) }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="取证请求消息编号">
              <span class="mono">{{ detailEvent.mediaRequestMsgId || '—' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="请求取证时间">
              {{ formatTime(detailEvent.mediaRequestedAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="已收视频 / 期望">
              {{ detailEvent.mediaCount ?? detailMedia.length }} / {{ detailEvent.mediaExpectedCount ?? 4 }}
            </el-descriptions-item>
            <el-descriptions-item label="事故描述" :span="2">
              {{ detailEvent.accidentDesc || '—' }}
            </el-descriptions-item>
          </el-descriptions>

          <h4 class="block-title">已归档视频（{{ detailMedia.length }}）</h4>
          <el-table :data="detailMedia" size="small" empty-text="尚未收到任何方向的事故视频">
            <el-table-column label="方向" width="90">
              <template #default="{ row }">{{ directionLabel(row.cameraDirection) }}</template>
            </el-table-column>
            <el-table-column label="类型" width="80">
              <template #default="{ row }">{{ row.mediaType || '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag
                  size="small"
                  :type="row.status === 'AVAILABLE' ? 'success' : row.status === 'MISSING' ? 'danger' : 'info'"
                  effect="plain"
                >
                  {{ row.status === 'AVAILABLE' ? '可用' : row.status === 'MISSING' ? '文件缺失' : '待落盘' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="大小" width="100" align="right">
              <template #default="{ row }">{{ formatBytes(row.fileSize) }}</template>
            </el-table-column>
            <el-table-column label="上传方" width="110">
              <template #default="{ row }">{{ row.uploadedBy || '—' }}</template>
            </el-table-column>
            <el-table-column label="归档时间" width="170">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="地址" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                <a v-if="row.url" :href="row.url" target="_blank" rel="noopener" class="link">{{ row.url }}</a>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 故障明细 ==================== -->
    <el-dialog v-model="faultDetailVisible" title="故障明细" width="900px" top="6vh">
      <div v-loading="faultDetailLoading">
        <template v-if="faultDetail">
          <el-descriptions :column="3" size="small" border>
            <el-descriptions-item label="记录 ID">{{ faultDetail.record.id }}</el-descriptions-item>
            <el-descriptions-item label="车辆 VIN">
              <span class="mono">{{ faultDetail.record.vin }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="上报时间">
              {{ formatTime(faultDetail.record.reportedAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="故障项数">{{ faultDetail.itemCount }}</el-descriptions-item>
            <el-descriptions-item label="最高严重度">
              <el-tag size="small" :type="severityTagType(faultDetail.record.maxSeverity)" effect="dark">
                {{ faultDetail.record.maxSeverity ?? '—' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="上报消息编号">
              <span class="mono">{{ faultDetail.record.msgId || '—' }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <h4 class="block-title">故障项</h4>
          <el-table :data="faultDetail.items" size="small" empty-text="该记录没有故障明细项">
            <el-table-column prop="errorCode" label="故障码" width="100">
              <template #default="{ row }">
                <span class="mono">{{ row.errorCode }}</span>
              </template>
            </el-table-column>
            <el-table-column label="故障名称" min-width="190">
              <template #default="{ row }">{{ faultCodeName(row.errorCode) }}</template>
            </el-table-column>
            <el-table-column label="分类" width="130">
              <template #default="{ row }">{{ faultCodeCategory(row.errorCode) || '—' }}</template>
            </el-table-column>
            <el-table-column label="严重度" width="90" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="severityTagType(Number(row.errorSeverity))" effect="plain">
                  {{ row.errorSeverity ?? '—' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="次数" width="76" align="right">
              <template #default="{ row }">{{ formatNumber(row.msgCount ?? 0) }}</template>
            </el-table-column>
            <el-table-column label="发生时间" width="170">
              <template #default="{ row }">{{ formatTime(row.errorTimestamp) }}</template>
            </el-table-column>
            <el-table-column prop="errorReason" label="原因" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.errorReason || '—' }}</template>
            </el-table-column>
          </el-table>
        </template>
      </div>
      <template #footer>
        <el-button @click="faultDetailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.header-actions {
  display: flex;
  gap: 8px;
}

.note {
  margin: 0 0 10px;
  font-size: 12.5px;
  line-height: 1.75;
}

.pending-panel {
  margin-bottom: 16px;
  border-left: 3px solid var(--dssad-danger);
}

.tabs-panel {
  padding-top: 6px;
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

.chart-title {
  margin-top: 22px;
}

.tiny {
  font-size: 11px;
  line-height: 1.4;
}

.complete-alert {
  margin-bottom: 14px;
}

.block-title {
  margin: 18px 0 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--dssad-text);
}

.media-steps {
  margin-bottom: 6px;
}

.link {
  color: var(--dssad-accent);
  text-decoration: none;
}

.link:hover {
  text-decoration: underline;
}
</style>
