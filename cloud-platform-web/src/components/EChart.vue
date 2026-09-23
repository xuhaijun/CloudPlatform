<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'

/**
 * ECharts 通用封装。
 *
 * 封装掉的三件「每个图表都要写一遍、且漏掉就出 Bug」的事：
 *   1. **销毁**：不调 `dispose()` 会在路由来回切换后累积实例，
 *      典型症状是切换十几次后页面明显卡顿（内存里堆着十几个 canvas）；
 *   2. **自适应尺寸**：监听 `window.resize` 是不够的 —— 侧边栏折叠、
 *      容器因 v-if 显示而宽度变化都不会触发 window 事件。这里用
 *      `ResizeObserver` 观察容器本身，覆盖全部场景；
 *   3. **setOption 的合并语义**：默认是合并，切换数据源后旧系列会残留；
 *      这里固定传 `notMerge = true`，让每次渲染都是「干净重画」。
 *
 * 使用方只需要把 option 当普通 prop 传进来。
 */
const props = withDefaults(
  defineProps<{
    option: echarts.EChartsCoreOption
    height?: string
    /**
     * 用 CSS `aspect-ratio` 固定绘图区比例（如 `'1 / 1'`）。
     * 传了它就忽略 `height`。
     *
     * 为什么需要它：轨迹回放要求**等比例**（否则南北向的路线会被拉扁，
     * 「轨迹看着不对」十有八九是比例失真而不是数据问题）。
     * 做法是「把数据空间补成正方形 + 容器也是正方形」，
     * 这样 1 个数据单位在 X/Y 上占的像素数就相同了。
     */
    aspect?: string
    loading?: boolean
    /** 空数据提示文案，避免用户对着空白画布猜原因 */
    emptyText?: string
  }>(),
  { height: '280px', aspect: '', loading: false, emptyText: '暂无数据' }
)

const container = ref<HTMLDivElement | null>(null)
const chart = shallowRef<echarts.ECharts | null>(null)
const isEmpty = ref(false)

let observer: ResizeObserver | null = null

function render() {
  const instance = chart.value
  if (!instance) return
  isEmpty.value = isOptionEmpty(props.option)
  if (isEmpty.value) {
    instance.clear()
    return
  }
  instance.setOption(props.option, true)
}

/**
 * 判断「是否无数据」。
 *
 * 只看 `series` 里每个系列的 data 是否都为空 —— 用 `notMerge` 重画时，
 * 空数组会渲染成一块空白画布，用户无法区分「没有数据」和「图表坏了」。
 */
function isOptionEmpty(option: echarts.EChartsCoreOption): boolean {
  const series = (option as { series?: unknown }).series
  if (!series) return false
  const list = Array.isArray(series) ? series : [series]
  return list.every((item) => {
    const data = (item as { data?: unknown[] }).data
    return Array.isArray(data) && data.length === 0
  })
}

function resize() {
  chart.value?.resize()
}

onMounted(() => {
  if (!container.value) return
  // 'dark' 主题与全局深色底一致，省掉逐项配置文字/坐标轴颜色的重复劳动
  chart.value = echarts.init(container.value, 'dark', { renderer: 'canvas' })
  render()

  observer = new ResizeObserver(() => resize())
  observer.observe(container.value)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  chart.value?.dispose()
  chart.value = null
})

watch(() => props.option, render)

watch(
  () => props.loading,
  (value) => {
    const instance = chart.value
    if (!instance) return
    if (value) {
      instance.showLoading('default', {
        text: '加载中',
        color: '#2f81f7',
        textColor: '#8b9bb0',
        maskColor: 'rgba(13, 17, 23, 0.6)'
      })
    } else {
      instance.hideLoading()
    }
  }
)
</script>

<template>
  <div class="chart-wrapper" :style="{ height }">
    <div ref="container" class="chart-canvas"></div>
    <div v-if="isEmpty && !loading" class="chart-empty">{{ emptyText }}</div>
  </div>
</template>

<style scoped>
.chart-wrapper {
  position: relative;
  width: 100%;
}

.chart-canvas {
  width: 100%;
  height: 100%;
}

.chart-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--dssad-text-dim);
  font-size: 13px;
  letter-spacing: 0.5px;
  pointer-events: none;
}
</style>
