import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api'
import {
  clearSession,
  getToken,
  TOKEN_STORAGE_KEY,
  USER_STORAGE_KEY
} from '@/api/http'

/**
 * 登录态。
 *
 * 令牌持久化在 `localStorage` 而不是内存：管理控制台经常被运维长期开着多个页签，
 * 内存态一旦刷新页面就得重新登录。代价是需要接受「同源脚本可读」这一事实 ——
 * 因此平台侧的令牌是有 30 分钟级别的有效期 + 可吊销的（TokenService），
 * 而不是长期有效的 JWT，泄露窗口可控。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(getToken())
  const username = ref<string | null>(localStorage.getItem(USER_STORAGE_KEY))
  /** 服务端提示「正在使用开发默认口令」时为 true，用于顶栏告警。 */
  const usingDefaultCredential = ref(false)

  const isAuthenticated = computed(() => !!token.value)

  async function login(user: string, password: string) {
    const result = await authApi.login(user, password)
    token.value = result.token
    username.value = result.username
    usingDefaultCredential.value = Boolean(result.usingDefaultCredential)

    localStorage.setItem(TOKEN_STORAGE_KEY, result.token)
    localStorage.setItem(USER_STORAGE_KEY, result.username)
    return result
  }

  async function logout() {
    try {
      // 服务端吊销失败（令牌本来就已失效/网络异常）不应阻塞本地登出：
      // 否则用户会「点了退出却还在登录态」，只能手动清缓存
      await authApi.logout()
    } catch {
      /* 忽略：本地清理才是登出的实质 */
    }
    reset()
  }

  /** 仅清理本地状态（401 时由 http 层触发）。 */
  function reset() {
    token.value = null
    username.value = null
    usingDefaultCredential.value = false
    clearSession()
  }

  return {
    token,
    username,
    isAuthenticated,
    usingDefaultCredential,
    login,
    logout,
    reset
  }
})
