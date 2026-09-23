import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Vite 配置。
 *
 * 代理规则按「浏览器侧路径前缀」划分，与后端的三类接口一一对应：
 *   /api        → 平台自有管理接口（/api/v1/**，需要 Token 或放行）
 *   /enterprise → 向监管平台开放的云云 HTTP 接口（HMAC 签名，非前端主用）
 *   /manager    → 监管平台侧回调上传（事故视频等）
 *   /media      → 本地媒体文件直出（开发期由后端 static 或 Nginx 提供）
 *
 * 开发期统一代理到后端 8080，从而**前端不需要任何 CORS 配置** ——
 * 生产由 Nginx 做同源反代，开发用 Vite 代理模拟同样的同源语义，
 * 避免「开发能跑、上线跨域」这类只在部署时才暴露的问题。
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.VITE_API_PROXY_TARGET || 'http://127.0.0.1:8080'

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      host: '127.0.0.1',
      port: 5173,
      proxy: {
        '/api': { target, changeOrigin: true },
        '/enterprise': { target, changeOrigin: true },
        '/manager': { target, changeOrigin: true },
        '/media': { target, changeOrigin: true },
        '/actuator': { target, changeOrigin: true }
      }
    },
    build: {
      outDir: 'dist',
      sourcemap: false,
      // ECharts / Element Plus 体积较大，拆包让首屏只加载用到的部分，
      // 避免「一个 3MB 的 vendor chunk 阻塞首屏」这类最典型的 SPA 性能问题
      rollupOptions: {
        output: {
          manualChunks: {
            vue: ['vue', 'vue-router', 'pinia'],
            element: ['element-plus', '@element-plus/icons-vue'],
            echarts: ['echarts']
          }
        }
      },
      chunkSizeWarningLimit: 1200
    }
  }
})
