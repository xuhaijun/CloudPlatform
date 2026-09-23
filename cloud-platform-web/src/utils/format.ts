/**
 * 时间/数值格式化工具。
 *
 * ============================ 后端序列化约定的两个坑 ============================
 * `spring.jackson.serialization.write-dates-as-timestamps: true`（见 application.yml）
 * 带来的结果与直觉不符：
 *
 * 1. `Instant` → **13 位毫秒数字**（不是 ISO 字符串）。
 *    好处是与《平台数据对接接口文档 1.4 节》「所有 timestamp 均为毫秒」一致；
 * 2. `LocalDate` → **数组** `[2026, 9, 22]`（Jackson 的 JavaTimeModule 在
 *    「写时间戳」模式下对日期类型的标准行为）。
 *
 * 因此所有时间展示都必须过 `formatTime`，不要在页面里直接 `new Date(value)`
 * —— 后者遇到数组会得到 `Invalid Date`，页面显示成 `NaN-NaN`。
 */

type DateLike = number | string | number[] | null | undefined

/**
 * 解析后端返回的时间值，失败返回 `null`。
 *
 * 兼容 5 种形态：毫秒时间戳、秒级时间戳、ISO 字符串、Date 数组、数字字符串。
 * 之所以要兼容到这种程度：接口文档与实现之间存在历史差异（有的字段按秒、
 * 有的按毫秒），前端在这里一次性兜住，好过在每个页面各写一遍判断。
 */
export function parseTime(value: DateLike): Date | null {
  if (value === null || value === undefined || value === '') return null

  // LocalDate 的数组形态：[年, 月, 日] 或 [年, 月, 日, 时, 分, 秒]
  if (Array.isArray(value)) {
    if (value.length < 3) return null
    const [y, m, d, hh = 0, mm = 0, ss = 0] = value
    return new Date(y, m - 1, d, hh, mm, ss)
  }

  if (typeof value === 'string') {
    // 纯数字字符串按时间戳处理
    if (/^\d+$/.test(value)) {
      return parseTime(Number(value))
    }
    const parsed = new Date(value)
    return Number.isNaN(parsed.getTime()) ? null : parsed
  }

  // 小于 10^11 视为秒级时间戳（10^11 毫秒 ≈ 1973 年，足以区分两种量纲）
  const millis = value < 1e11 ? value * 1000 : value
  const parsed = new Date(millis)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

function pad(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

/** 格式化为 `YYYY-MM-DD HH:mm:ss`。 */
export function formatTime(value: DateLike, fallback = '—'): string {
  const date = parseTime(value)
  if (!date) return fallback
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}

/** 格式化为 `YYYY-MM-DD`。 */
export function formatDate(value: DateLike, fallback = '—'): string {
  const date = parseTime(value)
  if (!date) return fallback
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** 格式化为 `MM-DD HH:mm`（图表轴、列表紧凑展示用）。 */
export function formatShort(value: DateLike, fallback = '—'): string {
  const date = parseTime(value)
  if (!date) return fallback
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/**
 * 相对时间（「3 分钟前」）。
 *
 * 车辆在线状态、队列水位这类信息用相对时间比绝对时间更有信息量：
 * 「最后心跳 4 小时前」一眼就知道是掉线，而 `2026-09-22 12:03:11` 需要心算。
 */
export function formatRelative(value: DateLike, fallback = '—'): string {
  const date = parseTime(value)
  if (!date) return fallback
  const diffSeconds = Math.round((Date.now() - date.getTime()) / 1000)
  if (diffSeconds < 0) return '刚刚'
  if (diffSeconds < 60) return `${diffSeconds} 秒前`
  if (diffSeconds < 3600) return `${Math.floor(diffSeconds / 60)} 分钟前`
  if (diffSeconds < 86400) return `${Math.floor(diffSeconds / 3600)} 小时前`
  return `${Math.floor(diffSeconds / 86400)} 天前`
}

/** 秒 → `1h 23m 45s`。 */
export function formatDuration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || seconds < 0) return '—'
  const total = Math.floor(seconds)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  if (h > 0) return `${h}h ${m}m ${s}s`
  if (m > 0) return `${m}m ${s}s`
  return `${s}s`
}

/** 字节 → 人类可读。 */
export function formatBytes(bytes: number | null | undefined): string {
  if (!bytes || bytes <= 0) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let index = 0
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024
    index += 1
  }
  return `${value.toFixed(index === 0 ? 0 : 1)} ${units[index]}`
}

/** 大数字千分位。 */
export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return value.toLocaleString('zh-CN')
}

/** 保留 n 位小数，空值返回 `—`。 */
export function formatDecimal(value: number | null | undefined, digits = 1): string {
  if (value === null || value === undefined) return '—'
  return value.toFixed(digits)
}

/**
 * 协议温度真值换算。
 *
 * 文档约定车端上报的 `temperature` 带 +100 偏移量（以整数传输负温度），
 * 后端已经提供派生的 `temperatureCelsius`；这里再兜一层是为了兼容
 * 老版本报文里只有原始值的场景。DB 保留原值便于对账。
 */
export function toCelsius(raw: number | null | undefined): number | null {
  if (raw === null || raw === undefined) return null
  return raw - 100
}
