<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { monitorApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { formatTime } from '@/utils/format'

/**
 * 应用外壳：左侧导航 + 顶栏 + 内容区。
 *
 * 顶栏里刻意放了两个「常驻可视化」：
 *   - **MQTT 连接状态**：平台绝大多数数据来自 MQTT，链路断了所有页面的数据都会变成
 *     静态旧值，而页面本身不会报错。把连接点常驻在顶栏，运维第一眼就能分辨
 *     「确实是没数据」还是「链路断了」；
 *   - **服务时间**：与车辆上报时间做对比时的基准。
 *
 * 健康检查的轮询间隔取 20 秒（比业务数据的 10 秒更慢）：它只是一个指示灯，
 * 高频轮询只会白白增加请求量。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const collapsed = ref(false)
const mqttConnected = ref<boolean | null>(null)
const serverTime = ref<number>(Date.now())
const clockText = ref('')

let healthTimer: number | undefined
let clockTimer: number | undefined

/** 菜单项：与 router 的业务路由一一对应，集中在此便于增删。 */
const menus = [
  { path: '/dashboard', title: '运营总览', icon: 'DataBoard' },
  { path: '/vehicles', title: '车辆管理', icon: 'Van' },
  { path: '/events', title: '事件中心', icon: 'Warning' },
  { path: '/operations', title: '运营调度', icon: 'Guide' },
  { path: '/remote-driving', title: '远程驾驶', icon: 'Headset' },
  { path: '/map-barriers', title: '地图阻断', icon: 'LocationInformation' },
  { path: '/video', title: '视频取证', icon: 'VideoCamera' },
  { path: '/monitor', title: '链路监控', icon: 'Monitor' }
]

const activeMenu = computed(() => {
  // 详情页 /vehicles/XXX 也要高亮「车辆管理」
  const matched = menus.find((m) => route.path === m.path || route.path.startsWith(`${m.path}/`))
  return matched?.path ?? route.path
})

const pageTitle = computed(() => (route.meta.title as string) ?? 'DSSAD 云平台')
const pageSubtitle = computed(() => (route.meta.subtitle as string) ?? '')

async function pollHealth() {
  try {
    const result = await monitorApi.healthCheck()
    mqttConnected.value = result.mqttConnected
    serverTime.value = Date.now()
  } catch {
    // 健康检查失败属于「服务不可达」，http 层已提示；此处置 null 让指示灯变灰
    mqttConnected.value = null
  }
}

function tickClock() {
  clockText.value = formatTime(Date.now())
}

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确认退出登录？', '退出确认', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return // 用户取消
  }
  await auth.logout()
  ElMessage.success('已退出登录')
  void router.replace({ name: 'login' })
}

onMounted(() => {
  void pollHealth()
  healthTimer = window.setInterval(pollHealth, 20_000)
  tickClock()
  clockTimer = window.setInterval(tickClock, 1_000)
})

onBeforeUnmount(() => {
  if (healthTimer) window.clearInterval(healthTimer)
  if (clockTimer) window.clearInterval(clockTimer)
})
</script>

<template>
  <el-container class="layout">
    <el-aside :width="collapsed ? '62px' : '208px'" class="aside">
      <div class="brand">
        <span class="brand-mark">D</span>
        <span v-if="!collapsed" class="brand-text">
          <strong>DSSAD 云平台</strong>
          <small>车路通 · 数据接入与运营</small>
        </span>
      </div>

      <el-menu
        :default-active="activeMenu"
        :collapse="collapsed"
        :collapse-transition="false"
        class="menu"
        router
      >
        <el-menu-item v-for="menu in menus" :key="menu.path" :index="menu.path">
          <el-icon><component :is="menu.icon" /></el-icon>
          <template #title>{{ menu.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-button text class="collapse-btn" @click="collapsed = !collapsed">
            <el-icon><Fold v-if="!collapsed" /><Expand v-else /></el-icon>
          </el-button>
          <div class="titles">
            <h1>{{ pageTitle }}</h1>
            <p v-if="pageSubtitle">{{ pageSubtitle }}</p>
          </div>
        </div>

        <div class="header-right">
          <el-tag
            :type="mqttConnected === true ? 'success' : mqttConnected === false ? 'danger' : 'info'"
            effect="dark"
            size="small"
            round
          >
            <span v-if="mqttConnected === true" class="live-dot" />
            MQTT {{ mqttConnected === true ? '已连接' : mqttConnected === false ? '未连接' : '未知' }}
          </el-tag>

          <span class="clock mono">{{ clockText }}</span>

          <el-tooltip v-if="auth.usingDefaultCredential" content="当前使用开发默认口令，上线前必须覆盖" placement="bottom">
            <el-tag type="warning" effect="dark" size="small" round>默认口令</el-tag>
          </el-tooltip>

          <el-dropdown trigger="click">
            <span class="user">
              <el-icon><UserFilled /></el-icon>
              {{ auth.username ?? '未登录' }}
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="handleLogout">
                  <el-icon><SwitchButton /></el-icon> 退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <!-- keep-alive 会缓存页面状态（滚动位置、筛选条件），
             但监控类页面必须实时，因此不缓存，每次进入都重新拉取 -->
        <router-view v-slot="{ Component }">
          <component :is="Component" />
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}

.aside {
  background: #10161e;
  border-right: 1px solid var(--dssad-border);
  transition: width 0.2s ease;
  overflow: hidden;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 58px;
  padding: 0 14px;
  border-bottom: 1px solid var(--dssad-border);
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  min-width: 28px;
  border-radius: 7px;
  background: linear-gradient(135deg, #2f81f7, #1a5fbf);
  color: #fff;
  font-weight: 700;
  font-size: 15px;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
  white-space: nowrap;
}

.brand-text strong {
  font-size: 13.5px;
  letter-spacing: 0.3px;
}

.brand-text small {
  font-size: 11px;
  color: var(--dssad-text-dim);
}

.menu {
  border-right: none;
  background: transparent;
  --el-menu-bg-color: transparent;
  --el-menu-hover-bg-color: #1a2330;
  --el-menu-active-color: #2f81f7;
  --el-menu-text-color: #a9b7c8;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 58px;
  padding: 0 18px 0 8px;
  background: var(--dssad-panel);
  border-bottom: 1px solid var(--dssad-border);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 6px;
}

.collapse-btn {
  color: var(--dssad-text-dim);
  font-size: 16px;
}

.titles h1 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.titles p {
  margin: 1px 0 0;
  font-size: 11.5px;
  color: var(--dssad-text-dim);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.clock {
  color: var(--dssad-text-dim);
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
}

.user {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  cursor: pointer;
  color: var(--dssad-text);
  font-size: 13px;
  outline: none;
}

.main {
  padding: 0;
  background: var(--dssad-bg);
  overflow-y: auto;
}
</style>
