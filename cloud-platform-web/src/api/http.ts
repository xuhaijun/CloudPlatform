import axios, { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

/**
 * HTTP 客户端。
 *
 * ============================ 三个必须知道的平台约定 ============================
 * 1. **HTTP 200 不代表成功**。平台所有接口都返回
 *    `{ code, message, data }`，错误码在 body 里（0000 成功、1001 参数、2001 未授权…）。
 *    如果只判 `status === 200`，会把「鉴权失败」当成「请求成功」，
 *    拿到 `data = null` 后在页面里显示 0 —— 这是最难排查的一类前端 Bug。
 *    （顺带一提：本项目的压测脚本就踩过同一个坑，见 perf/load_test.py 的 login 注释。）
 *
 * 2. **令牌走 `X-Token` 请求头，不是 `Authorization: Bearer`**。这是平台约定。
 *
 * 3. **时间字段是 13 位毫秒时间戳**（`spring.jackson.serialization.write-dates-as-timestamps=true`），
 *    不是 ISO 字符串；而 `LocalDate` 会被序列化成数组 `[2026,9,22]`。
 *    格式化统一走 `@/utils/format`，不要在这里做特判。
 *
 * 另外：响应拦截器把 `response.data` **就地替换为业务 data**，
 * 于是业务层拿到的就是解包后的对象；类型由下面导出的 get/post 包装函数保证。
 */

/** 平台统一响应体。 */
export interface ApiResponse<T> {
  code: string
  message: string
  data: T
}

/** 令牌在 localStorage 中的键名（与登录页共用，集中在此避免拼错）。 */
export const TOKEN_STORAGE_KEY = 'dssad.token'
/** 当前登录用户名，仅用于顶栏展示。 */
export const USER_STORAGE_KEY = 'dssad.username'

export const SUCCESS_CODE = '0000'
export const UNAUTHORIZED_CODE = '2001'

/** 业务异常：携带平台错误码，便于调用方按码分支处理。 */
export class ApiError extends Error {
  readonly code: string

  constructor(code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

/**
 * 401 处理钩子。
 *
 * 用「注册回调」而不是在这里直接 `import router`：http 层被 router 的守卫、
 * store、页面同时引用，直接引入 router 会造成循环依赖（Vite 下表现为
 * 某些模块拿到 undefined，且只在生产构建里复现）。
 */
let unauthorizedHandler: (() => void) | null = null

export function setUnauthorizedHandler(handler: () => void): void {
  unauthorizedHandler = handler
}

/** 会话失效：清掉本地令牌，避免后续请求继续带着废令牌打服务端。 */
export function clearSession(): void {
  localStorage.removeItem(TOKEN_STORAGE_KEY)
  localStorage.removeItem(USER_STORAGE_KEY)
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_STORAGE_KEY)
}

export class UnauthorizedError extends ApiError {
  constructor(message = '登录状态已失效，请重新登录') {
    super(UNAUTHORIZED_CODE, message)
    this.name = 'UnauthorizedError'
  }
}

const http = axios.create({
  // 留空 = 相对路径，由 Vite devServer 代理或生产 Nginx 同源反代承接
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  // 查询类接口都是毫秒级；15 秒足够覆盖「数据库抖动 + 首次 JIT」，
  // 又不至于让用户对着转圈等太久
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' }
})

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getToken()
  if (token) {
    config.headers.set('X-Token', token)
  }
  return config
})

http.interceptors.response.use(
  (response: AxiosResponse) => {
    const body = response.data as unknown

    // 非平台统一响应体（如 /actuator、静态媒体）原样透传
    if (!body || typeof body !== 'object' || !('code' in (body as object))) {
      return response
    }

    const api = body as ApiResponse<unknown>

    if (api.code === SUCCESS_CODE) {
      // 就地解包：业务层拿到的就是 data
      response.data = api.data
      return response
    }

    if (api.code === UNAUTHORIZED_CODE) {
      clearSession()
      unauthorizedHandler?.()
      // 不做 ElMessage 提示：跳登录页本身就是最强的提示，
      // 再加一条 toast 会在多个并发请求同时失效时叠出一屏红条
      return Promise.reject(new UnauthorizedError(api.message))
    }

    ElMessage.error(api.message || `请求失败（业务码 ${api.code}）`)
    return Promise.reject(new ApiError(api.code, api.message))
  },
  async (error: AxiosError) => {
    // ---- GET 幂等请求的网络级失败自动重试 1 次 ----
    // 场景：无线网络瞬断 / 代理抖动 / 首包超时。查询类请求无副作用，重试安全；
    // 服务端已应答的 4xx/5xx（error.response 存在）不重试 —— 那是确定性失败，重试只会拖时间。
    // 重试在拦截器里做而不是包装函数里做，保证 toast 只在「最终失败」时弹一次。
    const config = error.config as (InternalAxiosRequestConfig & { __retryCount?: number }) | undefined
    const networkFailure =
      !error.response && (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT' || error.code === 'ERR_NETWORK')
    if (config?.method === 'get' && networkFailure && (config.__retryCount ?? 0) < 1) {
      config.__retryCount = (config.__retryCount ?? 0) + 1
      await new Promise((resolve) => setTimeout(resolve, 500))
      return http.request(config)
    }

    // 网络层失败：区分「连不上」与「超时」，这两种的排查方向完全不同
    let message: string
    if (error.code === 'ECONNABORTED') {
      message = '请求超时，请检查服务端是否正常或稍后重试'
    } else if (!error.response) {
      message = '无法连接服务端，请确认后端已启动（默认 8080）'
    } else {
      message = `服务端返回 HTTP ${error.response.status}`
    }
    ElMessage.error(message)
    return Promise.reject(error)
  }
)

/** GET，返回解包后的业务数据。 */
export async function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  const response = await http.get<T>(url, { params })
  return response.data
}

/** POST，返回解包后的业务数据。 */
export async function post<T>(url: string, data?: unknown): Promise<T> {
  const response = await http.post<T>(url, data)
  return response.data
}

/**
 * 表单上传（事故视频取证），支持进度回调。
 *
 * 单独一个函数的原因：文件上限 200MB（文档 9.2），15 秒默认超时根本不够，
 * 且必须显式指定 multipart，否则 axios 会把 FormData 当 JSON 发出去。
 */
export async function upload<T>(
  url: string,
  form: FormData,
  onProgress?: (percent: number) => void
): Promise<T> {
  const response = await http.post<T>(url, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 10 * 60 * 1000,
    onUploadProgress: (event) => {
      if (!onProgress || !event.total) return
      onProgress(Math.round((event.loaded * 100) / event.total))
    }
  })
  return response.data
}

/**
 * 文件下载（CSV 导出等）。
 *
 * <p>与普通 GET 的区别：`responseType: 'blob'`（二进制不走统一响应体解包），
 * 且从 `Content-Disposition` 解析服务端文件名（含导出时间戳）。
 * 成功后创建临时 objectURL 触发浏览器下载，随后立即释放。
 */
export async function download(
  url: string,
  params?: Record<string, unknown>,
  fallbackName = 'export.csv'
): Promise<void> {
  const response = await http.get<Blob>(url, {
    params,
    responseType: 'blob',
    timeout: 2 * 60 * 1000
  })
  const disposition = (response.headers['content-disposition'] as string | undefined) ?? ''
  const match = disposition.match(/filename="?([^";]+)"?/i)
  const filename = match?.[1] ? decodeURIComponent(match[1]) : fallbackName

  const objectUrl = URL.createObjectURL(response.data)
  const anchor = document.createElement('a')
  anchor.href = objectUrl
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(objectUrl)
}

export default http
