<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { vehicleApi } from '@/api'
import type { Vehicle } from '@/api/types'
import { formatNumber, formatRelative, formatTime } from '@/utils/format'

/**
 * 车辆列表。
 *
 * 「在线」判定来自后端 `vehicle.online`（由心跳/报文驱动的落库字段），
 * 前端**不**用 `lastBaseAt` 自己算 —— 否则前端的超时阈值与后端不一致，
 * 会出现「列表显示在线但点进详情没有实时数据」的矛盾。
 */
const router = useRouter()

const query = reactive({
  keyword: '',
  vehicleType: '',
  online: undefined as boolean | undefined
})

const rows = ref<Vehicle[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await vehicleApi.list({
      keyword: query.keyword.trim() || undefined,
      vehicleType: query.vehicleType.trim() || undefined,
      online: query.online,
      page: page.value,
      size: size.value
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

function reset() {
  query.keyword = ''
  query.vehicleType = ''
  query.online = undefined
  page.value = 1
  void load()
}

function openDetail(vin: string) {
  void router.push({ name: 'vehicle-detail', params: { vin } })
}

/** 最近一次上报距今超过 10 分钟视为「数据陈旧」，用于给出额外提示。 */
function isStale(vehicle: Vehicle): boolean {
  const value = vehicle.lastBaseAt
  if (!value) return true
  const millis = typeof value === 'number' ? value : new Date(value as string).getTime()
  return Date.now() - millis > 10 * 60 * 1000
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>车辆管理</h2>
        <p class="subtitle">
          在线状态由后端心跳驱动；事故/故障累计数为车辆维度计数，点击车辆可查看轨迹与状态历史。
        </p>
      </div>
      <el-button size="small" :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
    </div>

    <div class="panel">
      <el-form :inline="true" class="filters" @submit.prevent="load">
        <el-form-item label="关键字">
          <el-input
            v-model="query.keyword"
            placeholder="VIN / 车牌 / 设备号"
            clearable
            style="width: 220px"
            @keyup.enter="load"
          />
        </el-form-item>
        <el-form-item label="车辆类型">
          <el-input v-model="query.vehicleType" placeholder="如 BUS / TRUCK" clearable style="width: 150px" />
        </el-form-item>
        <el-form-item label="在线">
          <el-select v-model="query.online" placeholder="全部" clearable style="width: 120px">
            <el-option label="在线" :value="true" />
            <el-option label="离线" :value="false" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="rows" size="small" empty-text="没有符合条件的车辆">
        <el-table-column prop="vin" label="VIN" min-width="180" show-overflow-tooltip />
        <el-table-column prop="plateNo" label="车牌" width="110">
          <template #default="{ row }">{{ row.plateNo || '—' }}</template>
        </el-table-column>
        <el-table-column prop="vehicleType" label="类型" width="96">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.vehicleType || '—' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <el-tag :type="row.online ? 'success' : 'info'" size="small" effect="dark">
              {{ row.online ? '在线' : '离线' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最后位置上报" width="170">
          <template #default="{ row }">
            <span :class="{ 'text-warn': isStale(row) }">
              {{ formatRelative(row.lastBaseAt) }}
            </span>
            <div class="muted tiny">{{ formatTime(row.lastBaseAt) }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="model" label="车型" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.model || '—' }}</template>
        </el-table-column>
        <el-table-column label="软件版本" width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.hwVersion || '—' }} / {{ row.adVersion || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="事故" width="76" align="right">
          <template #default="{ row }">
            <span :class="{ 'text-danger': (row.accidentCount ?? 0) > 0 }">
              {{ formatNumber(row.accidentCount ?? 0) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="故障" width="76" align="right">
          <template #default="{ row }">
            <span :class="{ 'text-warn': (row.faultCount ?? 0) > 0 }">
              {{ formatNumber(row.faultCount ?? 0) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="88" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row.vin)">
              详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        class="pager"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="load"
        @size-change="load"
      />
    </div>
  </div>
</template>

<style scoped>
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
</style>
