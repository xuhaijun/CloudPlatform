<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { operationApi } from '@/api'
import type { CockpitResource, RemoteDrivingRecord } from '@/api/types'
import StatCard from '@/components/StatCard.vue'
import { formatDuration, formatNumber, formatRelative, formatTime } from '@/utils/format'

/**
 * 远程驾驶：接管发起/结束 + 接管记录。
 *
 * ============================ 为什么远驾必须单独做一页 ============================
 * 远程驾驶接管是**强监管行为**：谁（驾驶员姓名 + 驾驶证号）、在哪个舱（台架号）、
 * 什么时刻接管了哪台车，都必须可追溯（文档 6.1.4.2.1）。因此：
 *   - 发起/结束都要同步上报监管平台，并**不依赖对端 ACK** 就落本地记录 ——
 *     监管平台抖动或 resp 丢失时，企业侧仍须能自证；
 *   - 「结束接管」返回 `false` 是**状态不一致**的信号（平台重启、状态存储丢 key 等），
 *     说明这次结束没有配对上有效的接管，必须如实提示，不能笼统报「成功」。
 *
 * 页面主体分两块：上方「当前占用」做发起/结束（有状态、要慎点），
 * 下方「接管记录」是只读台账。
 */

const query = reactive({
  vin: '',
  closed: false as boolean,
  page: 1,
  size: 20
})

const rows = ref<RemoteDrivingRecord[]>([])
const total = ref(0)
const loading = ref(false)

/** 当前占用查询。 */
const cockpitVin = ref('')
const cockpitLoading = ref(false)
const currentCockpitId = ref<string | null>(null)
const cockpitQueried = ref(false)
const lastAllocated = ref<CockpitResource | null>(null)

/** 发起接管时的发起人（1 交巡警 / 2 监管平台）。 */
const starter = ref('2')

async function load() {
  loading.value = true
  try {
    const result = await operationApi.remoteDriving({
      vin: query.vin.trim() || undefined,
      closed: query.closed,
      page: query.page,
      size: query.size
    })
    rows.value = result.list ?? []
    total.value = result.total ?? 0
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  query.vin = ''
  query.closed = false
  query.page = 1
  void load()
}

async function queryCockpit() {
  const vin = cockpitVin.value.trim()
  if (!vin) {
    ElMessage.warning('请先输入车辆 VIN')
    return
  }
  cockpitLoading.value = true
  try {
    const binding = await operationApi.currentCockpit(vin)
    currentCockpitId.value = binding.cockpitId ?? null
    cockpitQueried.value = true
  } catch {
    currentCockpitId.value = null
  } finally {
    cockpitLoading.value = false
  }
}

async function startTakeover() {
  const vin = cockpitVin.value.trim()
  if (!vin) {
    ElMessage.warning('请先输入车辆 VIN')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认为车辆 ${vin} 发起远程接管？\n` +
        '系统将按 LRU 分配远驾舱，并立即向监管平台上报接管事件（type=1）。',
      '发起远程接管',
      { confirmButtonText: '确认接管', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  cockpitLoading.value = true
  try {
    const cockpit = await operationApi.startCockpit(vin, starter.value)
    lastAllocated.value = cockpit
    currentCockpitId.value = cockpit.cockpitId
    cockpitQueried.value = true
    ElMessage.success(`已发起接管：舱 ${cockpit.cockpitId} / 驾驶员 ${cockpit.driver ?? '待分配'}`)
    await load()
  } catch {
    // 1001（starter 非法）等业务错误已由 http 层提示
  } finally {
    cockpitLoading.value = false
  }
}

async function endTakeover() {
  const vin = cockpitVin.value.trim()
  if (!vin) {
    ElMessage.warning('请先输入车辆 VIN')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认结束车辆 ${vin} 的远程接管？将向监管平台上报结束事件（type=2）。`,
      '结束远程接管',
      { confirmButtonText: '结束接管', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  cockpitLoading.value = true
  try {
    const existed = await operationApi.endCockpit(vin)
    currentCockpitId.value = null
    lastAllocated.value = null
    if (existed) {
      ElMessage.success('已结束接管并上报监管平台')
    } else {
      // 这不是失败，而是状态不一致：本地没有该车的接管绑定
      ElMessage.warning(
        '已向监管平台上报结束事件，但本地未找到进行中的接管绑定 —— 可能是平台重启导致状态丢失，请核对接管记录台账。'
      )
    }
    await load()
  } catch {
    // 同上
  } finally {
    cockpitLoading.value = false
  }
}

/** 驾驶类型：1 发起接管 / 2 结束接管。 */
function drivingTypeMeta(type?: string): { label: string; type: 'primary' | 'success' | 'info' } {
  if (type === '1') return { label: '发起接管', type: 'primary' }
  if (type === '2') return { label: '结束接管', type: 'info' }
  return { label: type ?? '未知', type: 'info' }
}

const activeCount = computed(() => (query.closed ? 0 : rows.value.filter((row) => !row.closed).length))

const longestDuration = computed(() => {
  const durations = rows.value.map((row) => row.durationSeconds ?? 0)
  return durations.length ? Math.max(...durations) : 0
})

/** 驾驶证号属于个人敏感信息，列表里打码，避免截图外泄。 */
function maskCard(card?: string): string {
  if (!card) return '—'
  if (card.length <= 6) return '****'
  return `${card.slice(0, 4)}****${card.slice(-2)}`
}

const DURATION_WARN_SECONDS = 2 * 60 * 60

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>远程驾驶</h2>
        <p class="subtitle">
          接管属强监管行为：驾驶员、驾驶证号、台架号全量留存；发起与结束都会同步上报监管平台。
        </p>
      </div>
      <el-button size="small" :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
    </div>

    <div class="stat-grid">
      <StatCard
        label="当前筛选口径"
        :value="query.closed ? '已结束' : '进行中'"
        :tone="query.closed ? 'default' : 'warn'"
        hint="切换筛选条件后重新查询"
        icon="Filter"
      />
      <StatCard
        label="进行中接管"
        :value="formatNumber(activeCount)"
        unit="台"
        :tone="activeCount > 0 ? 'warn' : 'ok'"
        hint="下方列表当前页的进行中记录数"
        icon="Headset"
      />
      <StatCard
        label="记录总数"
        :value="formatNumber(total)"
        unit="条"
        hint="符合当前筛选条件的接管记录"
        icon="Tickets"
      />
      <StatCard
        label="本页最长接管"
        :value="formatDuration(longestDuration)"
        :tone="longestDuration > DURATION_WARN_SECONDS ? 'danger' : 'default'"
        hint="超过 2 小时的接管需关注驾驶员疲劳风险"
        icon="AlarmClock"
      />
    </div>

    <!-- ========== 接管控制 ========== -->
    <div class="panel">
      <h3 class="panel-title">接管控制</h3>
      <p class="muted note">
        远驾舱按 <b>LRU（最久未使用）</b>分配，避免连续把任务压给同一个舱造成疲劳风险。
        发起接管后平台立刻上报 <span class="mono">remote-driving/up</span>（type=1）并落本地记录，
        <b>不等对端 ACK</b> —— 监管平台抖动时企业侧仍需能自证。
      </p>

      <el-form :inline="true" @submit.prevent="queryCockpit">
        <el-form-item label="车辆 VIN">
          <el-input
            v-model="cockpitVin"
            placeholder="TESTVIN0000000001"
            clearable
            style="width: 240px"
            @keyup.enter="queryCockpit"
          />
        </el-form-item>
        <el-form-item label="发起人">
          <el-select v-model="starter" style="width: 150px">
            <el-option label="监管平台（2）" value="2" />
            <el-option label="交巡警（1）" value="1" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button :loading="cockpitLoading" @click="queryCockpit">查询当前舱位</el-button>
          <el-button type="primary" :loading="cockpitLoading" @click="startTakeover">发起接管</el-button>
          <el-button type="danger" plain :loading="cockpitLoading" @click="endTakeover">结束接管</el-button>
        </el-form-item>
      </el-form>

      <div v-if="cockpitQueried" class="cockpit-result">
        <el-descriptions :column="3" size="small" border>
          <el-descriptions-item label="车辆 VIN">
            <span class="mono">{{ cockpitVin }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="当前占用舱位">
            <el-tag v-if="currentCockpitId" size="small" type="warning" effect="dark">
              {{ currentCockpitId }}
            </el-tag>
            <span v-else class="muted">无进行中的接管</span>
          </el-descriptions-item>
          <el-descriptions-item label="发起人">
            {{ starter === '1' ? '交巡警' : '监管平台' }}
          </el-descriptions-item>
          <template v-if="lastAllocated">
            <el-descriptions-item label="本次分配驾驶员">
              {{ lastAllocated.driver || '待分配' }}
            </el-descriptions-item>
            <el-descriptions-item label="驾驶证号">
              <span class="mono">{{ maskCard(lastAllocated.driverLicenseNumber) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="联系手机">
              {{ lastAllocated.mobile || '—' }}
            </el-descriptions-item>
          </template>
        </el-descriptions>
        <p class="muted note">
          驾驶证号在界面上打码展示：它是取证要素，需要留档但不需要在日常页面裸露。
        </p>
      </div>
    </div>

    <!-- ========== 接管记录台账 ========== -->
    <div class="panel">
      <h3 class="panel-title">接管记录</h3>
      <el-form :inline="true" class="filters" @submit.prevent="load">
        <el-form-item label="VIN">
          <el-input
            v-model="query.vin"
            placeholder="车辆 VIN"
            clearable
            style="width: 220px"
            @keyup.enter="load"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.closed" style="width: 150px" @change="load">
            <el-option label="进行中" :value="false" />
            <el-option label="已结束" :value="true" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <el-alert
        class="type-alert"
        type="info"
        :closable="false"
        show-icon
        title="为什么分「进行中 / 已结束」两个口径"
        description="协议没有提供「接管单号」，平台以「该车最近一条未结束的发起记录」与结束报文配对。因此一旦出现开始报文丢失，结束记录就会配不上对，只在这两个口径里查不出异常 —— 需要结合下面「记录异常」列一起看。"
      />

      <el-table v-loading="loading" :data="rows" size="small" row-key="id" empty-text="没有符合条件的接管记录">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="vin" label="VIN" min-width="150" show-overflow-tooltip />
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="drivingTypeMeta(row.drivingType).type" effect="dark">
              {{ drivingTypeMeta(row.drivingType).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="驾驶员" width="110">
          <template #default="{ row }">{{ row.driver || '—' }}</template>
        </el-table-column>
        <el-table-column label="驾驶证号" width="150">
          <template #default="{ row }">
            <span class="mono">{{ maskCard(row.driverCard) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="远驾舱" width="130" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.remoteCockpitNo || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="事件时间" width="170">
          <template #default="{ row }">
            <div>{{ formatTime(row.occurredAt) }}</div>
            <div class="muted tiny">{{ formatRelative(row.occurredAt) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="持续时长" width="120" align="right">
          <template #default="{ row }">
            <span
              v-if="row.durationSeconds != null"
              :class="{ 'text-warn': row.durationSeconds > DURATION_WARN_SECONDS }"
            >
              {{ formatDuration(row.durationSeconds) }}
            </span>
            <span v-else class="muted">进行中</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.closed ? 'info' : 'warning'" effect="plain">
              {{ row.closed ? '已结束' : '进行中' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="记录异常" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="!row.driver && !row.closed" class="text-warn">
              发起记录缺驾驶员（协议必传项）
            </span>
            <span v-else-if="row.closed && row.durationSeconds == null" class="text-warn">
              已结束但无时长（未配对到发起记录）
            </span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        class="pager"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="load"
        @size-change="load"
      />
    </div>
  </div>
</template>

<style scoped>
.note {
  margin: 0 0 10px;
  font-size: 12.5px;
  line-height: 1.75;
}

.cockpit-result {
  margin-top: 6px;
}

.filters {
  margin-bottom: 4px;
}

.filters :deep(.el-form-item) {
  margin-bottom: 12px;
}

.type-alert {
  margin-bottom: 14px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.tiny {
  font-size: 11px;
  line-height: 1.4;
}
</style>
