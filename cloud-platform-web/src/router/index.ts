import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { setUnauthorizedHandler } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import AppLayout from '@/layout/AppLayout.vue'

/**
 * 路由表。
 *
 * 设计要点：
 * 1. **业务页面全部懒加载**（`() => import(...)`）。ECharts + Element Plus 体积可观，
 *    首屏只需要登录页与布局壳；不做懒加载会让首屏 JS 里塞进 9 个页面的代码。
 * 2. 布局壳（AppLayout）**不懒加载**：它是所有业务页的父级，懒加载反而会多一次
 *    级联请求，且首屏必然需要它。
 * 3. `meta.title` 驱动顶栏面包屑，省掉在每个页面里各写一遍标题的重复。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/',
    component: AppLayout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '运营总览', subtitle: '车辆在线率、事故/故障态势与链路健康' }
      },
      {
        path: 'vehicles',
        name: 'vehicles',
        component: () => import('@/views/VehicleListView.vue'),
        meta: { title: '车辆管理', subtitle: '车辆列表、在线状态与运行状态查询' }
      },
      {
        path: 'vehicles/:vin',
        name: 'vehicle-detail',
        component: () => import('@/views/VehicleDetailView.vue'),
        meta: { title: '车辆详情', subtitle: '实时状态、轨迹回放与准静态参数变更史' }
      },
      {
        path: 'events',
        name: 'events',
        component: () => import('@/views/EventCenterView.vue'),
        meta: { title: '事件中心', subtitle: '事故取证闭环与故障明细' }
      },
      {
        path: 'operations',
        name: 'operations',
        component: () => import('@/views/OperationView.vue'),
        meta: { title: '运营调度', subtitle: '轨迹任务下发与执行跟踪' }
      },
      {
        path: 'remote-driving',
        name: 'remote-driving',
        component: () => import('@/views/RemoteDrivingView.vue'),
        meta: { title: '远程驾驶', subtitle: '接管舱位、进行中接管与历史记录' }
      },
      {
        path: 'map-barriers',
        name: 'map-barriers',
        component: () => import('@/views/MapBarrierView.vue'),
        meta: { title: '地图阻断', subtitle: '监管平台下发的阻断设施与生效时段' }
      },
      {
        path: 'video',
        name: 'video',
        component: () => import('@/views/VideoView.vue'),
        meta: { title: '视频取证', subtitle: '事故媒体上传与取证状态跟踪' }
      },
      {
        path: 'monitor',
        name: 'monitor',
        component: () => import('@/views/MonitorView.vue'),
        meta: { title: '链路监控', subtitle: 'MQTT 管道水位、报文留痕与缓存命中率' }
      }
    ]
  },
  // 兜底：未知路径回总览，避免用户看到空白页
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach((to) => {
  // 在此处（而非模块顶层）取 store：模块求值时 Pinia 尚未安装
  const auth = useAuthStore()

  if (to.meta.public) {
    // 已登录还去登录页 → 直接回总览，避免出现「登录态下的登录页」这一矛盾界面
    return auth.isAuthenticated ? { path: '/dashboard' } : true
  }

  if (!auth.isAuthenticated) {
    // 带上 redirect，登录后能回到用户原本想去的页面
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  return true
})

// 令牌失效的统一处理：任何接口返回业务码 2001 都清会话并跳登录。
// 用回调注册而不是在 http 层 import router，避免循环依赖（见 api/http.ts 注释）。
setUnauthorizedHandler(() => {
  const auth = useAuthStore()
  auth.reset()
  const current = router.currentRoute.value
  if (current.name !== 'login') {
    void router.replace({ name: 'login', query: { redirect: current.fullPath } })
  }
})

export default router
