<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, type UploadFile, type UploadFiles, type UploadInstance } from 'element-plus'
import { dictApi, eventApi, mediaApi, simulatorApi } from '@/api'
import type { AccidentEvent, DictEntry, MediaAsset } from '@/api/types'
import StatCard from '@/components/StatCard.vue'
import { formatBytes, formatNumber, formatRelative, formatTime } from '@/utils/format'

/**
 * 视频取证：事故媒体上传 + 取证进度跟踪。
 *
 * ============================ 这个页面的真实身份：取证链路的联调台 ============================
 * 事故视频上传接口（文档 5.2.2.2.1）的正常调用方是**车端**或**监管平台**，不是运维。
 * 那为什么前端还要做这个页面？因为这条链路是全系统最长、最容易在联调时卡住的一条：
 *
 *     事故上报 → 平台下发 accident-media/down → 车端应答 → 上传四方向视频 → 状态归档
 *
 * 而这四个环节跨三个团队（车端 / 企业云端 / 监管平台）。没有本地联调台时，
 * 每次验证都要等真实车端配合；有了它，可以用模拟器一键造事故，
 * 再按原样复现车端的上传请求，把整条链在本地跑通。
 *
 * ============================ 上传接口的鉴权与常见错误 ============================
 * 注意：`/manager/api/v1/video/accident-media-upload` **不**走 X-Token 拦截器
 * （见 WebConfig，只拦 `/api/v1/**`），它的授权是**业务态**的：
 *
 *   - `ackMsgId` 必须等于该事故已下发的 `mediaRequestMsgId`，否则返回 2001；
 *   - `vin` 必须与事故所属车辆一致，否则 2001；
 *   - `cameraDirection` 必须是 1/2/3/4，否则 1001；
 *   - 该事故已处于 MEDIA_FAILED（车端拒绝）时不再接收上传；
 *   - **同方向重复上传是幂等的** —— 返回既有记录，不会产生第二条视频。
 *
 * 为什么不用签名：multipart 二进制体无法参与签名计算；而「只能上传平台请求过的事件」
 * 这条业务规则已经足以阻止任意视频污染证据链（冒充车端上传需要先知道 ackMsgId）。
 */

/** 摄像头方向字典（1 前 / 2 右 / 3 后 / 4 左），由后端下发，前端不硬编码。 */
const cameraDirections = ref<DictEntry[]>([])

/** 待取证事故（超时未收到取证的挂起事故）——相当于按优先级排好的工作队列。 */
const pending = ref<AccidentEvent[]>([])
const pendingLoading = ref(false)

/** 上传表单。 */
const form = reactive({
  vin: '',
  eventId: '',
  ackMsgId: '',
  cameraDirection: '1',
  msgId: '',
  fileName: ''
})
const selectedFile = ref<File | null>(null)
const uploadRef = ref<UploadInstance>()
const uploading = ref(false)
const percent = ref(0)
const lastResult = ref<{ mediaId?: number; url?: string; directionName?: string; fileSize?: number } | null>(null)

/** 取证进度跟踪：选定事故后展示四方向归档情况。 */
const trackEventId = ref('')
const trackLoading = ref(false)
const trackEvent = ref<AccidentEvent | null>(null)
const trackMedia = ref<MediaAsset[]>([])
const trackComplete = ref(false)

/** 模拟器（仅本地/联调启用）。 */
const simulatorVin = ref('TESTVIN0000000001')
const simulatorLoading = ref(false)

/** 取出某个方向已归档的视频。 */
function mediaOf(direction: string): MediaAsset | undefined {
  return trackMedia.value.find((item) => item.cameraDirection === direction)
}

function directionLabel(value: string): string {
  return cameraDirections.value.find((item) => item.value === value)?.label ?? `方向 ${value}`
}

/** 四方向编号固定为 1/2/3/4（文档约定），不依赖字典顺序。 */
const DIRECTION_CODES = ['1', '2', '3', '4']

const archivedCount = computed(
  () => DIRECTION_CODES.filter((code) => mediaOf(code)).length
)

const totalSize = computed(() =>
  trackMedia.value.reduce((sum, item) => sum + (item.fileSize ?? 0), 0)
)

/** 上传前置校验：四个字段缺一不可，提前拦下比等服务端报 1001 更省时间。 */
const formReady = computed(
  () => Boolean(form.vin.trim() && form.eventId.trim() && form.ackMsgId.trim() && selectedFile.value)
)

function newMsgId(): string {
  // 本次上传的消息编号，车端场景由车端生成；联调时前端生成一个即可
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `WEB-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
}

// ==================== 数据加载 ====================

async function loadDict() {
  try {
    cameraDirections.value = await dictApi.cameraDirections()
  } catch {
    cameraDirections.value = []
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

async function loadTrack() {
  const eventId = trackEventId.value.trim()
  if (!eventId) {
    ElMessage.warning('请输入或从待取证清单选择事故 ID')
    return
  }
  trackLoading.value = true
  try {
    const detail = await eventApi.accidentDetail(eventId)
    trackEvent.value = detail.event ?? null
    trackMedia.value = detail.media ?? []
    trackComplete.value = detail.mediaComplete ?? false
  } catch {
    trackEvent.value = null
    trackMedia.value = []
    trackComplete.value = false
  } finally {
    trackLoading.value = false
  }
}

// ==================== 表单联动 ====================

/**
 * 从待取证清单一键带入表单。
 *
 * `ackMsgId` 必须来自事故的 `mediaRequestMsgId`，这是唯一合法的凭据 ——
 * 手抄极易出错，因此这里直接带入，并在界面上显式提示「不要手改」。
 */
function fillFromEvent(row: AccidentEvent) {
  form.vin = row.vin
  form.eventId = row.eventId
  form.ackMsgId = row.mediaRequestMsgId ?? ''
  form.msgId = newMsgId()
  trackEventId.value = row.eventId
  if (!form.ackMsgId) {
    ElMessage.warning('该事故还没有 mediaRequestMsgId，说明平台尚未下发媒体请求，此时上传会被拒绝')
  }
}

/** 从待取证清单直接跳到该事故的取证进度（省掉手抄 eventId）。 */
function openProgressOf(row: AccidentEvent) {
  trackEventId.value = row.eventId
  void loadTrack()
}

function onFileChange(file: UploadFile, files: UploadFiles) {
  selectedFile.value = (file.raw as File | undefined) ?? null
  form.fileName = file.name ?? ''
  // el-upload 默认保留历史文件，这里只留最新一个，避免「传了 A 实际发出 B」
  if (files.length > 1) {
    files.splice(0, files.length - 1)
  }
}

function clearFile() {
  selectedFile.value = null
  form.fileName = ''
  uploadRef.value?.clearFiles()
}

async function submitUpload() {
  if (!formReady.value) {
    ElMessage.warning('vin / 事故 ID / ackMsgId / 文件 均为必填')
    return
  }
  const file = selectedFile.value
  if (!file) return

  uploading.value = true
  percent.value = 0
  lastResult.value = null
  try {
    const result = await mediaApi.uploadAccidentMedia(
      {
        vin: form.vin.trim(),
        msgId: form.msgId.trim() || newMsgId(),
        ackMsgId: form.ackMsgId.trim(),
        eventId: form.eventId.trim(),
        cameraDirection: form.cameraDirection
      },
      file,
      (value) => {
        percent.value = value
      }
    )
    lastResult.value = {
      mediaId: typeof result.mediaId === 'number' ? result.mediaId : undefined,
      url: typeof result.url === 'string' ? result.url : undefined,
      directionName: typeof result.directionName === 'string' ? result.directionName : undefined,
      fileSize: typeof result.fileSize === 'number' ? result.fileSize : undefined
    }
    ElMessage.success(
      `已归档 ${result.directionName || directionLabel(form.cameraDirection)} 视频` +
        '（同方向重复上传是幂等的，返回的是既有记录）'
    )
    // 归档成功后刷新进度与待取证清单，让「闭环推进」立刻可见
    if (trackEventId.value.trim() === form.eventId.trim()) {
      await loadTrack()
    } else {
      trackEventId.value = form.eventId.trim()
      await loadTrack()
    }
    await loadPending()
    clearFile()
  } catch {
    // 2001（ackMsgId 不匹配 / vin 不一致）、1001（方向非法）等已由 http 层提示
  } finally {
    uploading.value = false
  }
}

// ==================== 模拟器 ====================

async function simulateAccident() {
  const vin = simulatorVin.value.trim()
  if (!vin) {
    ElMessage.warning('请输入 VIN')
    return
  }
  simulatorLoading.value = true
  try {
    const result = await simulatorApi.scenarioAccident(vin)
    const eventId = typeof result.eventId === 'string' ? result.eventId : ''
    const ackMsgId = typeof result.mediaRequestMsgId === 'string' ? result.mediaRequestMsgId : ''
    form.vin = vin
    form.eventId = eventId
    form.ackMsgId = ackMsgId
    form.msgId = newMsgId()
    trackEventId.value = eventId
    ElMessage.success('模拟事故已按生产链路处理（解析 → 校验 → 去重 → 业务 → ACK → 留痕），表单已自动填充')
    await Promise.all([loadTrack(), loadPending()])
  } catch {
    ElMessage.error(
      '模拟器不可用：该接口仅在 dssad.simulator.enabled=true 时注册（生产环境默认关闭，返回 404 属预期行为）'
    )
  } finally {
    simulatorLoading.value = false
  }
}

onMounted(() => {
  void loadDict()
  void loadPending()
  form.msgId = newMsgId()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>视频取证</h2>
        <p class="subtitle">
          事故视频上传的授权是<b>业务态</b>的：ackMsgId 必须等于平台下发的取证请求编号，同方向重复上传幂等。
        </p>
      </div>
      <el-button size="small" :loading="pendingLoading" @click="loadPending">
        <el-icon><Refresh /></el-icon> 刷新清单
      </el-button>
    </div>

    <div class="stat-grid">
      <StatCard
        label="待取证事故"
        :value="formatNumber(pending.length)"
        unit="起"
        :tone="pending.length > 0 ? 'danger' : 'ok'"
        hint="超 10 分钟未收到取证的挂起事故"
        icon="Timer"
      />
      <StatCard
        label="已归档方向"
        :value="`${archivedCount} / 4`"
        :tone="archivedCount >= 4 ? 'ok' : archivedCount > 0 ? 'warn' : 'default'"
        hint="文档 3.3 要求四方向（前/右/后/左）取证"
        icon="VideoCamera"
      />
      <StatCard
        label="已归档体积"
        :value="formatBytes(totalSize)"
        hint="当前跟踪事故的已归档视频总大小"
        icon="FolderOpened"
      />
      <StatCard
        label="单文件上限"
        :value="200"
        unit="MB"
        hint="文档 9.2 约定；后端 dssad.storage.max-upload-bytes 同值"
        icon="Files"
      />
    </div>

    <!-- ========== 待取证事故清单 ========== -->
    <div class="panel">
      <h3 class="panel-title">待取证事故（按等待时长优先处理）</h3>
      <p class="muted note">
        视频有严格时效（事故前 15s + 后 5s），拖过时限就<b>永久丢失</b>。
        点「带入上传」会把 vin / eventId / ackMsgId 自动填进下方表单 ——
        ackMsgId 是唯一合法凭据，手抄极易出错。
      </p>
      <el-table
        v-loading="pendingLoading"
        :data="pending"
        size="small"
        empty-text="没有挂起的事故，取证链路是通的"
      >
        <el-table-column label="事故 ID" min-width="180" show-overflow-tooltip>
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
        <el-table-column label="已收/期望" width="100" align="center">
          <template #default="{ row }">
            <span :class="{ 'text-danger': (row.mediaCount ?? 0) < (row.mediaExpectedCount ?? 4) }">
              {{ row.mediaCount ?? 0 }} / {{ row.mediaExpectedCount ?? 4 }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="取证凭据（ackMsgId）" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.mediaRequestMsgId" class="mono">{{ row.mediaRequestMsgId }}</span>
            <span v-else class="text-warn">尚未下发媒体请求</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="fillFromEvent(row)">带入上传</el-button>
            <el-button
              link
              size="small"
              @click="
                () => {
                  trackEventId = row.eventId
                  loadTrack()
                }
              "
            >
              进度
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- ========== 上传表单 ========== -->
    <div class="panel">
      <h3 class="panel-title">事故媒体上传（复现车端请求）</h3>
      <p class="muted note">
        接口：<span class="mono">POST /manager/api/v1/video/accident-media-upload</span>。
        注意后端的 5 个参数<b>全部是 query 参数</b>，只有文件走 multipart body ——
        把 vin/file 一起塞进 FormData 是最常见的对接错误。
        该接口不在 X-Token 拦截范围内（WebConfig 只拦 /api/v1/**），授权靠 ackMsgId 的业务校验。
      </p>

      <el-form :model="form" label-width="130px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="车辆 VIN" required>
              <el-input v-model="form.vin" placeholder="必须与事故所属车辆一致，否则返回 2001" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="事故 ID" required>
              <el-input v-model="form.eventId" placeholder="事故事件 ID（eventId）" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="ackMsgId" required>
              <el-input v-model="form.ackMsgId" placeholder="必须等于平台下发的 mediaRequestMsgId" />
              <div class="form-hint muted">
                授权凭据：不匹配时后端明确拒绝（2001），防止任意视频污染证据链。
              </div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="摄像头方向" required>
              <el-select v-model="form.cameraDirection" style="width: 100%">
                <el-option
                  v-for="item in cameraDirections"
                  :key="item.value"
                  :label="`${item.value} · ${item.label}`"
                  :value="item.value ?? ''"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="消息编号 msgId">
              <el-input v-model="form.msgId" placeholder="本次上传的消息编号">
                <template #append>
                  <el-button @click="form.msgId = newMsgId()">重新生成</el-button>
                </template>
              </el-input>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="视频文件" required>
              <el-upload
                ref="uploadRef"
                :auto-upload="false"
                :limit="1"
                :show-file-list="true"
                drag
                :on-change="onFileChange"
              >
                <el-icon class="upload-icon"><UploadFilled /></el-icon>
                <div class="upload-text">
                  拖拽视频到此处，或<em>点击选择</em>（单文件 ≤ 200MB）
                </div>
              </el-upload>
            </el-form-item>
          </el-col>
        </el-row>

        <el-progress v-if="uploading || percent > 0" :percentage="percent" :stroke-width="14" class="progress" />

        <div class="actions">
          <el-button type="primary" :loading="uploading" :disabled="!formReady" @click="submitUpload">
            上传并归档
          </el-button>
          <el-button :disabled="uploading" @click="clearFile">清空文件</el-button>
          <span v-if="form.fileName" class="muted">已选文件：{{ form.fileName }}</span>
        </div>
      </el-form>

      <el-descriptions v-if="lastResult" :column="3" size="small" border class="result">
        <el-descriptions-item label="媒体 ID">{{ lastResult.mediaId ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="方向">
          {{ lastResult.directionName || directionLabel(form.cameraDirection) }}
        </el-descriptions-item>
        <el-descriptions-item label="大小">{{ formatBytes(lastResult.fileSize) }}</el-descriptions-item>
        <el-descriptions-item label="可访问地址" :span="3">
          <a v-if="lastResult.url" :href="lastResult.url" target="_blank" rel="noopener" class="link">
            {{ lastResult.url }}
          </a>
          <span v-else class="muted">—</span>
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <!-- ========== 取证进度 ========== -->
    <div class="panel">
      <h3 class="panel-title">取证进度跟踪</h3>
      <el-form :inline="true" @submit.prevent="loadTrack">
        <el-form-item label="事故 ID">
          <el-input
            v-model="trackEventId"
            placeholder="eventId"
            clearable
            style="width: 300px"
            @keyup.enter="loadTrack"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="trackLoading" @click="loadTrack">查询进度</el-button>
        </el-form-item>
      </el-form>

      <template v-if="trackEvent">
        <el-alert
          :type="trackComplete ? 'success' : 'warning'"
          :closable="false"
          show-icon
          :title="trackComplete ? '四方向视频已齐备' : `取证要素不齐：已归档 ${archivedCount} / 4 个方向`"
          :description="
            trackComplete
              ? '满足文档 3.3 的取证要求，可以进入事故复盘环节。'
              : '缺失的方向无法事后补齐（车端只保留事故前 15s + 后 5s 的缓存）。请按下方清单逐个方向上传。'
          "
          class="complete-alert"
        />

        <div class="direction-grid">
          <div
            v-for="code in DIRECTION_CODES"
            :key="code"
            class="direction-card"
            :class="{ missing: !mediaOf(code) }"
          >
            <div class="direction-head">
              <span class="direction-name">{{ directionLabel(code) }}</span>
              <el-tag size="small" :type="mediaOf(code) ? 'success' : 'danger'" effect="dark">
                {{ mediaOf(code) ? '已归档' : '缺失' }}
              </el-tag>
            </div>
            <template v-if="mediaOf(code)">
              <div class="direction-row">
                <span class="muted">大小</span>
                <span>{{ formatBytes(mediaOf(code)?.fileSize) }}</span>
              </div>
              <div class="direction-row">
                <span class="muted">类型</span>
                <span class="mono">{{ mediaOf(code)?.mediaType || '—' }}</span>
              </div>
              <div class="direction-row">
                <span class="muted">归档时间</span>
                <span>{{ formatTime(mediaOf(code)?.createdAt) }}</span>
              </div>
              <div class="direction-row">
                <span class="muted">状态</span>
                <el-tag
                  size="small"
                  :type="
                    mediaOf(code)?.status === 'AVAILABLE'
                      ? 'success'
                      : mediaOf(code)?.status === 'MISSING'
                        ? 'danger'
                        : 'info'
                  "
                  effect="plain"
                >
                  {{ mediaOf(code)?.status || '—' }}
                </el-tag>
              </div>
              <a
                v-if="mediaOf(code)?.url"
                :href="mediaOf(code)?.url"
                target="_blank"
                rel="noopener"
                class="link direction-link"
              >
                查看视频
              </a>
            </template>
            <p v-else class="muted direction-empty">
              该方向尚无视频。可在上方表单把「摄像头方向」选为 {{ code }} 后上传。
            </p>
          </div>
        </div>

        <el-descriptions :column="3" size="small" border class="track-detail">
          <el-descriptions-item label="事故 ID">
            <span class="mono">{{ trackEvent.eventId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="车辆 VIN">
            <span class="mono">{{ trackEvent.vin }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="取证状态">
            {{ trackEvent.mediaStatus || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="取证请求编号" :span="2">
            <span class="mono">{{ trackEvent.mediaRequestMsgId || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="请求时间">
            {{ formatTime(trackEvent.mediaRequestedAt) }}
          </el-descriptions-item>
        </el-descriptions>
      </template>
      <p v-else class="muted note">
        输入事故 ID 后查询，可看到四个方向的归档情况与缺失项。
      </p>
    </div>

    <!-- ========== 联调模拟器 ========== -->
    <div class="panel simulator-panel">
      <h3 class="panel-title">
        联调模拟器
        <el-tag type="warning" size="small" effect="dark">仅本地/测试启用</el-tag>
      </h3>
      <p class="muted note">
        一键注入车端事故报文（走的是<b>生产链路</b>：解析 → 校验 → 去重 → 业务 → ACK → 留痕），
        返回的 <span class="mono">mediaRequestMsgId</span> 就是随后上传时要填的 ackMsgId。
        该接口由 <span class="mono">dssad.simulator.enabled</span> 控制，生产配置显式关闭 ——
        它能凭空造出事故记录，误开会让脏数据进入监管取证链路。
      </p>
      <el-form :inline="true" @submit.prevent="simulateAccident">
        <el-form-item label="VIN">
          <el-input v-model="simulatorVin" placeholder="TESTVIN0000000001" style="width: 240px" />
        </el-form-item>
        <el-form-item>
          <el-button type="warning" plain :loading="simulatorLoading" @click="simulateAccident">
            一键造事故并填充表单
          </el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
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

.upload-icon {
  font-size: 26px;
  color: var(--dssad-text-dim);
}

.upload-text {
  font-size: 12.5px;
  color: var(--dssad-text-dim);
}

.upload-text em {
  color: var(--dssad-accent);
  font-style: normal;
}

.progress {
  margin: 6px 0 14px;
}

.actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.result {
  margin-top: 16px;
}

.complete-alert {
  margin-bottom: 14px;
}

.direction-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
}

.direction-card {
  background: var(--dssad-panel-2);
  border: 1px solid var(--dssad-border);
  border-left: 3px solid var(--dssad-ok);
  border-radius: 8px;
  padding: 12px 14px;
}

.direction-card.missing {
  border-left-color: var(--dssad-danger);
}

.direction-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.direction-name {
  font-weight: 600;
  font-size: 13.5px;
}

.direction-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12.5px;
  line-height: 1.9;
}

.direction-empty {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
}

.direction-link {
  display: inline-block;
  margin-top: 8px;
  font-size: 12.5px;
}

.link {
  color: var(--dssad-accent);
  text-decoration: none;
  word-break: break-all;
}

.link:hover {
  text-decoration: underline;
}

.track-detail {
  margin-top: 16px;
}

.simulator-panel {
  border-left: 3px solid var(--dssad-warn);
}
</style>
