<script setup lang="ts">
import { computed } from 'vue'

/**
 * 统计卡片。
 *
 * 把「指标名 + 数值 + 单位 + 语义色 + 副标题」统一成一个组件，
 * 是为了让所有统计卡的大小、间距、数字字重完全一致 ——
 * 大屏上十几个卡片各自手写 CSS 时，1px 的对齐差异肉眼可见。
 *
 * `tone` 不是装饰：运维看大屏时是「扫视」而不是「阅读」，
 * 告警项必须靠颜色在 0.2 秒内被捕捉到（在线率低=红、队列积压=黄）。
 */
const props = withDefaults(
  defineProps<{
    label: string
    value: string | number
    unit?: string
    /** 语义色：默认中性、ok 正常、warn 需关注、danger 告警 */
    tone?: 'default' | 'ok' | 'warn' | 'danger'
    /** 副标题：口径说明或补充信息，例如「近 24 小时」 */
    hint?: string
    icon?: string
  }>(),
  { tone: 'default', unit: '', hint: '', icon: '' }
)

const toneClass = computed(() => `tone-${props.tone}`)
</script>

<template>
  <div class="stat-card" :class="toneClass">
    <div class="stat-head">
      <span class="stat-label">{{ label }}</span>
      <el-icon v-if="icon" class="stat-icon"><component :is="icon" /></el-icon>
    </div>
    <div class="stat-value">
      <span class="number">{{ value }}</span>
      <span v-if="unit" class="unit">{{ unit }}</span>
    </div>
    <div v-if="hint" class="stat-hint">{{ hint }}</div>
  </div>
</template>

<style scoped>
.stat-card {
  background: var(--dssad-panel);
  border: 1px solid var(--dssad-border);
  border-left: 3px solid var(--dssad-border);
  border-radius: 10px;
  padding: 13px 15px 12px;
  transition: border-color 0.2s ease, transform 0.2s ease;
}

.stat-card:hover {
  transform: translateY(-1px);
}

.stat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.stat-label {
  color: var(--dssad-text-dim);
  font-size: 12.5px;
}

.stat-icon {
  color: var(--dssad-text-dim);
  font-size: 14px;
}

.stat-value {
  display: flex;
  align-items: baseline;
  gap: 4px;
  margin-top: 7px;
}

.number {
  font-size: 23px;
  font-weight: 650;
  letter-spacing: -0.4px;
  font-variant-numeric: tabular-nums;
}

.unit {
  font-size: 12px;
  color: var(--dssad-text-dim);
}

.stat-hint {
  margin-top: 5px;
  font-size: 11.5px;
  color: var(--dssad-text-dim);
  line-height: 1.5;
}

.tone-ok {
  border-left-color: var(--dssad-ok);
}

.tone-ok .number {
  color: var(--dssad-ok);
}

.tone-warn {
  border-left-color: var(--dssad-warn);
}

.tone-warn .number {
  color: var(--dssad-warn);
}

.tone-danger {
  border-left-color: var(--dssad-danger);
}

.tone-danger .number {
  color: var(--dssad-danger);
}

.tone-default {
  border-left-color: var(--dssad-accent);
}
</style>
