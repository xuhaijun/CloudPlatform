<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { operationApi } from '@/api'
import type { NavigationRoute, TrackTask } from '@/api/types'
import EChart from '@/components/EChart.vue'
import { formatDuration, formatNumber, formatTime } from '@/utils/format'

/**
 * 运营调度：轨迹任务下发 + 导航路径查看。
 *
 * ============================ 任务下发的两个坑 ============================
 * 1. `action` **必填**（后端 `@NotBlank`），取值 `1` 开始 / `2` 结束。
 *    这不是可有可无的可选参数：漏传后端直接返回 1001 参数错误。
 * 2. 结束任务时 `taskId` 必须与开始时的**完全一致**。后端以 taskId 为幂等键配对
 *    「开始 → 结束」；若结束时报了个新 taskId，后端配对不上，会落一条
 *    `ORPHAN_FINISH` 孤儿记录（界面用红色标出，属于需要人工核查的数据缺失）。
 *    因此本页在 action=2 时强制要求填写 taskId。
 *
 * 导航路径的数据形态与轨迹不同：路径点是「起点 + 途经点 + 终点」的完整规划线，
 * 点数可达数千，不适合与上报轨迹混在一起看，因此单独一个页签。
 */

/** 页签（同 EventCenterView：Tabs 的 modelValue 是 string | number，不要用窄字面量联合类型）。 */
const activeTab = ref('task')

// ==================== 轨迹任务 ====================

const taskQuery = reactive({
  vin: '',
  status: undefined as string | undefined,
  page: 1,
  size: 20
})

const tasks = ref<TrackTask[]>([])
const taskTotal = ref(0)
const taskLoading = ref(false)

const dispatchVisible = ref(false)
const dispatching = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  vin: '',
  /** 1 开始 / 2 结束 */
  action: '1',
  taskId: '',
  startPosition: '',
  startLat: undefined as number | undefined,
  startLng: undefined as number | undefined,
  endPosition: '',
  endLat: undefined as number | undefined,
  endLng: undefined as number | undefined
})

const rules: FormRules = {
  vin: [{ required: true, message: '请输入车辆 VIN', trigger: 'blur' }],
  action: [{ required: true, message: '请选择任务动作', trigger: 'change' }],
  taskId: [
    {
      // 结束任务必须复用开始时的 taskId，否则后端会落成 ORPHAN_FINISH 孤儿记录
      validator: (_rule, value: string, callback) => {
        if (form.action === '2' && !value) {
          callback(new Error('结束任务必须填写开始时的任务 ID，否则会落成孤儿记录'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}

function statusMeta(status?: string): { label: string; type: 'success' | 'warning' | 'danger' | 'primary' } {
  switch (status) {
    case 'RUNNING':
      return { label: '进行中', type: 'primary' }
    case 'FINISHED':
      return { label: '已结束', type: 'success' }
    case 'ORPHAN_FINISH':
      return { label: '孤儿记录', type: 'danger' }
    default:
      return { label: status ?? '未知', type: 'warning' }
  }
}

async function loadTasks() {
  taskLoading.value = true
  try {
    const result = await operationApi.tasks({
      vin: taskQuery.vin.trim() || undefined,
      status: taskQuery.status,
      page: taskQuery.page,
      size: taskQuery.size
    })
    tasks.value = result.list ?? []
    taskTotal.value = result.total ?? 0
  } catch {
    tasks.value = []
    taskTotal.value = 0
  } finally {
    taskLoading.value = false
  }
}

function resetTaskQuery() {
  taskQuery.vin = ''
  taskQuery.status = undefined
  taskQuery.page = 1
  void loadTasks()
}

function openDispatch(preset?: TrackTask) {
  form.vin = preset?.vin ?? ''
  form.action = '1'
  form.taskId = ''
  form.startPosition = ''
  form.startLat = undefined
  form.startLng = undefined
  form.endPosition = ''
  form.endLat = undefined
  form.endLng = undefined
  dispatchVisible.value = true
}

/** 点「结束此任务」：预填 vin/taskId 并锁定动作为「结束」，避免手抄 taskId 抄错。 */
function openFinish(row: TrackTask) {
  openDispatch(row)
  form.action = '2'
  form.taskId = row.taskId
  form.startPosition = row.startPosition ?? ''
  form.endPosition = row.endPosition ?? ''
}

async function submitDispatch() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  dispatching.value = true
  try {
    // 坐标顺序是 [纬度, 经度]，与文档一致（不是常见的 [lng, lat]）
    const startPoint =
      form.startLat !== undefined && form.startLng !== undefined
        ? ([form.startLat, form.startLng] as [number, number])
        : undefined
    const endPoint =
      form.endLat !== undefined && form.endLng !== undefined
        ? ([form.endLat, form.endLng] as [number, number])
        : undefined

    const task = await operationApi.dispatchTask({
      vin: form.vin.trim(),
      action: form.action,
      taskId: form.taskId.trim() || undefined,
      startPosition: form.startPosition.trim() || undefined,
      startPoint,
      endPosition: form.endPosition.trim() || undefined,
      endPoint
    })

    ElMessage.success(
      form.action === '1'
        ? `任务已下发并上报监管平台：${task.taskId}`
        : `任务已结束：${task.taskId}（${formatDuration(task.durationSeconds)}）`
    )
    dispatchVisible.value = false
    await loadTasks()
  } catch {
    // http 层已弹出业务码对应的错误提示（如 1001 参数错误）
  } finally {
    dispatching.value = false
  }
}

// ==================== 导航路径 ====================

const routeVin = ref('')
const routeRows = ref<NavigationRoute[]>([])
const routeTotal = ref(0)
const routePage = ref(1)
const routeSize = ref(10)
const routeLoading = ref(false)
const latestRoute = ref<NavigationRoute | null>(null)
const routeError = ref('')
const selectedRoute = ref<NavigationRoute | null>(null)

async function loadRoutes() {
  const vin = routeVin.value.trim()
  if (!vin) {
    ElMessage.warning('请先输入车辆 VIN')
    return
  }
  routeLoading.value = true
  routeError.value = ''
  try {
    const result = await operationApi.routes(vin, { page: routePage.value, size: routeSize.value })
    routeRows.value = result.list ?? []
    routeTotal.value = result.total ?? 0

    // 最新路径可能不存在（后端会返回 404 业务码），单独 try 以免影响分页列表
    try {
      latestRoute.value = await operationApi.latestRoute(vin)
      selectedRoute.value = latestRoute.value
      routeError.value = ''
    } catch {
      latestRoute.value = null
      selectedRoute.value = routeRows.value[0] ?? null
      routeError.value = '该车辆暂无导航路径记录（groute 报文未上报）'
    }
  } catch {
    routeRows.value = []
    routeTotal.value = 0
    latestRoute.value = null
    selectedRoute.value = null
  } finally {
    routeLoading.value = false
  }
}

/** 点列表某行 → 在地图上回放该条路径。 */
function selectRoute(row: NavigationRoute) {
  selectedRoute.value = row
}

/**
 * 解析路径点 JSON（`[[lat,lng],...]`）。
 *
 * 后端存的是「纬度在前」的数组；ECharts 的坐标系是 `[x, y]`，
 * 我们统一转成 `[经度, 纬度]` 再画，否则地图会沿对角线镜像 ——
 * 表现为「路线看着像，但方向全反了」，很容易被误判成数据错了。
 */
function parsePathPoints(raw?: string): Array<[number, number]> {
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw) as number[][]
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter((point) => Array.isArray(point) && point.length >= 2)
      .map((point) => [Number(point[1]), Number(point[0])] as [number, number])
      .filter(([lng, lat]) => Number.isFinite(lng) && Number.isFinite(lat))
  } catch {
    // 路径点是「整体消费」的数据，解析失败直接返回空，由空态提示兜底
    return []
  }
}

/**
 * 路径平面图（等比例）。
 *
 * 与轨迹回放同一套做法：把经纬度投影到本地米平面并把数据空间补成正方形，
 * 容器再用 `aspect-ratio: 1/1`，这样 1 米横纵占的像素数相同，
 * 路线不会被拉扁。不接第三方地图 SDK —— 路径点已是完整规划线，
 * 叠加底图只会引入底图合规与加载失败两类额外风险。
 */
const routeOption = computed(() => {
  const points = parsePathPoints(selectedRoute.value?.pathPoints)
  if (points.length === 0) {
    return { series: [] }
  }

  const lngs = points.map(([lng]) => lng)
  const lats = points.map(([, lat]) => lat)
  const centerLng = (Math.min(...lngs) + Math.max(...lngs)) / 2
  const centerLat = (Math.min(...lats) + Math.max(...lats)) / 2
  // 经度方向按纬度做余弦修正（同样的度数，纬度越高对应的实际距离越短）
  const lngScale = Math.cos((centerLat * Math.PI) / 180)
  // 以「米」为单位比较两个方向的跨度，取较大者作为正方形半边长
  const spanX = (Math.max(...lngs) - Math.min(...lngs)) * lngScale
  const spanY = Math.max(...lats) - Math.min(...lats)
  const half = (Math.max(spanX, spanY) / 2) * 1.15 || 0.001

  const xMin = centerLng - half / lngScale
  const xMax = centerLng + half / lngScale
  const yMin = centerLat - half
  const yMax = centerLat + half

  const first = points[0]
  const last = points[points.length - 1]

  return {
    grid: { left: 8, right: 8, top: 8, bottom: 8 },
    tooltip: {
      trigger: 'item',
      formatter: (params: { seriesName?: string; value?: number[] }) => {
        const value = params.value ?? []
        return `${params.seriesName}<br/>经度 ${value[0]?.toFixed(6)}<br/>纬度 ${value[1]?.toFixed(6)}`
      }
    },
    xAxis: { type: 'value', min: xMin, max: xMax, show: false },
    yAxis: { type: 'value', min: yMin, max: yMax, show: false },
    series: [
      {
        name: '导航路径',
        type: 'line',
        showSymbol: false,
        lineStyle: { width: 3, color: '#2f81f7' },
        data: points
      },
      {
        name: '起点',
        type: 'scatter',
        symbolSize: 11,
        itemStyle: { color: '#3fb950' },
        data: [first]
      },
      {
        name: '终点',
        type: 'scatter',
        symbolSize: 11,
        itemStyle: { color: '#f85149' },
        data: [last]
      }
    ]
  }
})

const routePointCount = computed(() => parsePathPoints(selectedRoute.value?.pathPoints).length)

onMounted(() => {
  void loadTasks()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>运营调度</h2>
        <p class="subtitle">
          任务下发会同时上报监管平台并本地落库；任务配对键是 taskId，结束时必须复用开始时的 ID。
        </p>
      </div>
      <el-button size="small" type="primary" @click="openDispatch()">
        <el-icon><Promotion /></el-icon> 下发任务
      </el-button>
    </div>

    <div class="panel tabs-panel">
      <el-tabs v-model="activeTab">
        <!-- ==================== 轨迹任务 ==================== -->
        <el-tab-pane label="轨迹任务" name="task">
          <el-form :inline="true" class="filters" @submit.prevent="loadTasks">
            <el-form-item label="VIN">
              <el-input
                v-model="taskQuery.vin"
                placeholder="车辆 VIN"
                clearable
                style="width: 200px"
                @keyup.enter="loadTasks"
              />
            </el-form-item>
            <el-form-item label="状态">
              <el-select v-model="taskQuery.status" placeholder="全部" clearable style="width: 150px">
                <el-option label="进行中" value="RUNNING" />
                <el-option label="已结束" value="FINISHED" />
                <el-option label="孤儿记录" value="ORPHAN_FINISH" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadTasks">查询</el-button>
              <el-button @click="resetTaskQuery">重置</el-button>
            </el-form-item>
          </el-form>

          <el-table v-loading="taskLoading" :data="tasks" size="small" empty-text="没有符合条件的任务">
            <el-table-column prop="taskId" label="任务 ID" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="mono">{{ row.taskId }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="vin" label="VIN" min-width="150" show-overflow-tooltip />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="statusMeta(row.status).type" effect="dark">
                  {{ statusMeta(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="起点" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">
                <div>{{ row.startPosition || '—' }}</div>
                <div v-if="row.startLatitude != null" class="muted tiny mono">
                  {{ row.startLatitude?.toFixed(6) }}, {{ row.startLongitude?.toFixed(6) }}
                </div>
              </template>
            </el-table-column>
            <el-table-column label="终点" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">
                <div>{{ row.endPosition || '—' }}</div>
                <div v-if="row.endLatitude != null" class="muted tiny mono">
                  {{ row.endLatitude?.toFixed(6) }}, {{ row.endLongitude?.toFixed(6) }}
                </div>
              </template>
            </el-table-column>
            <el-table-column label="开始时间" width="170">
              <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
            </el-table-column>
            <el-table-column label="结束时间" width="170">
              <template #default="{ row }">{{ formatTime(row.finishedAt) }}</template>
            </el-table-column>
            <el-table-column label="时长" width="110" align="right">
              <template #default="{ row }">{{ formatDuration(row.durationSeconds) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.status === 'RUNNING'"
                  link
                  type="warning"
                  size="small"
                  @click="openFinish(row)"
                >
                  结束任务
                </el-button>
                <span v-else class="muted tiny">—</span>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-model:current-page="taskQuery.page"
            v-model:page-size="taskQuery.size"
            class="pager"
            :total="taskTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next, jumper"
            @current-change="loadTasks"
            @size-change="loadTasks"
          />

          <el-alert
            v-if="tasks.some((item) => item.status === 'ORPHAN_FINISH')"
            class="orphan-alert"
            type="error"
            :closable="false"
            show-icon
            title="存在孤儿任务记录"
            description="只收到「结束任务」报文却没找到对应「开始」记录。常见原因是车端重启后丢了 start 报文，或结束时报了不同的 taskId。这类任务需要人工核查，不能当作正常完成。"
          />
        </el-tab-pane>

        <!-- ==================== 导航路径 ==================== -->
        <el-tab-pane label="导航路径" name="route">
          <el-form :inline="true" class="filters" @submit.prevent="loadRoutes">
            <el-form-item label="VIN">
              <el-input
                v-model="routeVin"
                placeholder="车辆 VIN"
                clearable
                style="width: 220px"
                @keyup.enter="loadRoutes"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="routeLoading" @click="loadRoutes">查询</el-button>
            </el-form-item>
          </el-form>

          <el-alert
            v-if="routeError"
            class="route-alert"
            type="info"
            :closable="false"
            show-icon
            :title="routeError"
          />

          <div class="route-layout">
            <div class="route-panel">
              <h3 class="panel-title">路径平面图（等比例）</h3>
              <p class="muted note">
                不接第三方地图底图：路径点已是完整规划线，叠加底图只会引入合规与加载失败两类风险。
                横纵比例为 1:1，经度已做纬度余弦修正，路线不会被拉扁。
              </p>
              <EChart
                :option="routeOption"
                aspect="1 / 1"
                :loading="routeLoading"
                empty-text="选择左侧任一条路径查看图形"
              />
              <div v-if="selectedRoute" class="route-meta">
                <el-descriptions :column="3" size="small" border>
                  <el-descriptions-item label="上报时间">
                    {{ formatTime(selectedRoute.reportedAt) }}
                  </el-descriptions-item>
                  <el-descriptions-item label="路径点数">
                    {{ formatNumber(routePointCount) }}
                    <span v-if="selectedRoute.pointCount" class="muted">/ 报文字段 {{ formatNumber(selectedRoute.pointCount) }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="报文编号">
                    <span class="mono">{{ selectedRoute.msgId || '—' }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="起点" :span="3">
                    <span v-if="selectedRoute.startLatitude != null" class="mono">
                      {{ selectedRoute.startLatitude?.toFixed(6) }}, {{ selectedRoute.startLongitude?.toFixed(6) }}
                    </span>
                    <span v-else class="muted">—</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="终点" :span="3">
                    <span v-if="selectedRoute.endLatitude != null" class="mono">
                      {{ selectedRoute.endLatitude?.toFixed(6) }}, {{ selectedRoute.endLongitude?.toFixed(6) }}
                    </span>
                    <span v-else class="muted">—</span>
                  </el-descriptions-item>
                </el-descriptions>
              </div>
            </div>

            <div class="route-list">
              <h3 class="panel-title">路径记录</h3>
              <el-table
                v-loading="routeLoading"
                :data="routeRows"
                size="small"
                height="520"
                highlight-current-row
                empty-text="输入 VIN 查询该车的导航路径"
                @row-click="selectRoute"
              >
                <el-table-column label="上报时间" width="170">
                  <template #default="{ row }">
                    <div>{{ formatTime(row.reportedAt) }}</div>
                    <div v-if="row.id === latestRoute?.id" class="muted tiny text-ok">最新</div>
                  </template>
                </el-table-column>
                <el-table-column label="路径点" width="90" align="right">
                  <template #default="{ row }">{{ formatNumber(row.pointCount ?? 0) }}</template>
                </el-table-column>
                <el-table-column label="起点 → 终点" min-width="220" show-overflow-tooltip>
                  <template #default="{ row }">
                    <div v-if="row.startLatitude != null" class="mono tiny">
                      {{ row.startLatitude?.toFixed(4) }},{{ row.startLongitude?.toFixed(4) }}
                      →
                      {{ row.endLatitude?.toFixed(4) }},{{ row.endLongitude?.toFixed(4) }}
                    </div>
                    <span v-else class="muted">—</span>
                  </template>
                </el-table-column>
              </el-table>

              <el-pagination
                v-model:current-page="routePage"
                v-model:page-size="routeSize"
                class="pager"
                small
                :total="routeTotal"
                layout="total, prev, pager, next"
                @current-change="loadRoutes"
              />
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- ==================== 任务下发 ==================== -->
    <el-dialog v-model="dispatchVisible" title="下发 / 结束轨迹任务" width="720px">
      <p class="muted note">
        平台会做两件事：向监管平台上报 <span class="mono">task/up</span>（文档 6.1.4.2.2），
        并在本地落一条任务记录，使任务列表有数据可查。
      </p>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="车辆 VIN" prop="vin">
          <el-input v-model="form.vin" placeholder="TESTVIN0000000001" />
        </el-form-item>
        <el-form-item label="任务动作" prop="action">
          <el-radio-group v-model="form.action">
            <el-radio-button value="1">开始任务</el-radio-button>
            <el-radio-button value="2">结束任务</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="任务 ID" prop="taskId">
          <el-input
            v-model="form.taskId"
            :placeholder="form.action === '2' ? '必须与开始时的任务 ID 一致' : '留空则由平台生成 TASK-xxx'"
          />
          <div class="form-hint muted">
            任务配对键。留空时平台自动生成；<b>结束任务必须复用开始时的值</b>，
            否则后端找不到开始记录，会落一条 ORPHAN_FINISH 孤儿记录。
          </div>
        </el-form-item>

        <el-divider content-position="left">起点</el-divider>
        <el-form-item label="起点描述">
          <el-input v-model="form.startPosition" placeholder="如：成都市天府大道北段" />
        </el-form-item>
        <el-form-item label="起点坐标">
          <div class="point-row">
            <el-input-number
              v-model="form.startLat"
              :precision="6"
              :step="0.0001"
              :controls="false"
              placeholder="纬度"
              style="width: 160px"
            />
            <span class="muted">纬度</span>
            <el-input-number
              v-model="form.startLng"
              :precision="6"
              :step="0.0001"
              :controls="false"
              placeholder="经度"
              style="width: 160px"
            />
            <span class="muted">经度</span>
          </div>
          <div class="form-hint muted">顺序是 [纬度, 经度]，与接口文档一致 —— 不是常见的 [lng, lat]。</div>
        </el-form-item>

        <el-divider content-position="left">终点</el-divider>
        <el-form-item label="终点描述">
          <el-input v-model="form.endPosition" placeholder="如：成都市双流国际机场" />
        </el-form-item>
        <el-form-item label="终点坐标">
          <div class="point-row">
            <el-input-number
              v-model="form.endLat"
              :precision="6"
              :step="0.0001"
              :controls="false"
              placeholder="纬度"
              style="width: 160px"
            />
            <span class="muted">纬度</span>
            <el-input-number
              v-model="form.endLng"
              :precision="6"
              :step="0.0001"
              :controls="false"
              placeholder="经度"
              style="width: 160px"
            />
            <span class="muted">经度</span>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dispatchVisible = false">取消</el-button>
        <el-button type="primary" :loading="dispatching" @click="submitDispatch">
          {{ form.action === '1' ? '下发任务' : '结束任务' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
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

.note {
  margin: 0 0 10px;
  font-size: 12.5px;
  line-height: 1.75;
}

.tiny {
  font-size: 11px;
  line-height: 1.4;
}

.form-hint {
  font-size: 11.5px;
  line-height: 1.6;
  margin-top: 4px;
}

.point-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.orphan-alert,
.route-alert {
  margin-top: 14px;
}

.route-layout {
  display: grid;
  grid-template-columns: minmax(360px, 1fr) minmax(320px, 1fr);
  gap: 16px;
}

@media (max-width: 1200px) {
  .route-layout {
    grid-template-columns: 1fr;
  }
}

.route-meta {
  margin-top: 14px;
}

.route-list :deep(.el-table__row) {
  cursor: pointer;
}
</style>
