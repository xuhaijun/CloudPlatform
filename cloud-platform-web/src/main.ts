import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import 'element-plus/dist/index.css'
// 深色主题变量：与 index.html 上的 class="dark" 配套。
// 运营控制台默认深色 —— 大屏/监控场景下深色底能显著降低长时间盯屏的疲劳，
// 也让红/黄/绿告警色更突出。
import 'element-plus/theme-chalk/dark/css-vars.css'
import '@/styles/index.css'

import App from './App.vue'
import router from './router'

const app = createApp(App)

// 图标全量注册：控制台里图标用得非常散（菜单、按钮、统计卡），
// 逐个按需 import 带来的心智负担大于它省下的几十 KB（图标本身会被 tree-shaking 到单独 chunk）。
for (const [name, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, component)
}

app.use(createPinia())
// 路由必须先于挂载注册：router 的全局守卫里会读取 Pinia store，
// 顺序颠倒会在首屏导航时抛 "getActivePinia was called with no active Pinia"。
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
