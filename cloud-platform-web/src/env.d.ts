/// <reference types="vite/client" />

/**
 * SFC 类型声明。
 *
 * Vite 在构建时用 esbuild 处理 .vue（不做类型检查），
 * 因此这份声明是给 IDE / vue-tsc 用的：让 `import Foo from './Foo.vue'` 有类型。
 */
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}

interface ImportMetaEnv {
  /** 接口基地址；留空 = 相对路径（走同源代理） */
  readonly VITE_API_BASE_URL: string
  readonly VITE_APP_TITLE: string
  /** 轮询间隔毫秒 */
  readonly VITE_POLL_INTERVAL_MS: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
