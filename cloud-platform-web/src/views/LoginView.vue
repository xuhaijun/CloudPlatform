<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

/**
 * 登录页。
 *
 * 平台的鉴权约定（见 api/http.ts）：
 *   - 成功后服务端返回 `{ token, username, usingDefaultCredential, notice }`；
 *   - 后续所有请求通过 `X-Token` 头带令牌（不是 Authorization）；
 *   - 令牌失效时接口返回 **HTTP 200 + 业务码 2001**，由 http 层统一跳回本页。
 *
 * 因此本页只负责「取令牌 + 存下来 + 跳转」，不做任何权限判断
 * —— 权限判断只发生在服务端，前端隐藏菜单拦不住任何人。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const formRef = ref()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入口令', trigger: 'blur' }]
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const result = await auth.login(form.username.trim(), form.password)
    if (result.usingDefaultCredential) {
      ElMessage.warning('服务端正在使用开发默认口令，上线前请用环境变量覆盖')
    }
    ElMessage.success('登录成功')

    // 回到被拦截前的目标页面；没有则进总览
    const redirect = (route.query.redirect as string) || '/dashboard'
    await router.replace(redirect)
  } catch {
    // 用户名/口令错误由 http 层统一提示（业务码 2001），这里不重复弹
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  // 退出登录后回到本页时自动聚焦，省掉一次点击（运维登出/登录是高频动作）
  formRef.value?.$el?.querySelector('input')?.focus?.()
})
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="head">
        <span class="mark">D</span>
        <div>
          <h1>车路通 DSSAD 产品云平台</h1>
          <p>车辆数据接入 · 事故取证 · 运营调度 · 链路监控</p>
        </div>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="submit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" autocomplete="username">
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="口令"
            show-password
            autocomplete="current-password"
          >
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>

        <el-button type="primary" class="submit" :loading="loading" @click="submit">
          登 录
        </el-button>
      </el-form>

      <div class="tips">
        <p>
          <el-icon><InfoFilled /></el-icon>
          令牌通过 <code>X-Token</code> 请求头传递；失效时接口返回 HTTP 200 + 业务码
          <code>2001</code>，区别于 HTTP 401。
        </p>
        <p>
          <el-icon><Key /></el-icon>
          生产口令必须通过环境变量 <code>DSSAD_ADMIN_USER</code> /
          <code>DSSAD_ADMIN_PASSWORD</code> 注入，禁止使用开发默认值。
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background:
    radial-gradient(1200px 600px at 20% -10%, rgba(47, 129, 247, 0.16), transparent),
    radial-gradient(900px 500px at 100% 100%, rgba(63, 185, 80, 0.1), transparent),
    var(--dssad-bg);
}

.login-card {
  width: 404px;
  padding: 30px 32px 24px;
  background: var(--dssad-panel);
  border: 1px solid var(--dssad-border);
  border-radius: 14px;
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.42);
}

.head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 26px;
}

.mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  min-width: 40px;
  border-radius: 10px;
  background: linear-gradient(135deg, #2f81f7, #1a5fbf);
  color: #fff;
  font-weight: 700;
  font-size: 19px;
}

.head h1 {
  margin: 0;
  font-size: 16.5px;
  font-weight: 650;
  letter-spacing: 0.2px;
}

.head p {
  margin: 3px 0 0;
  font-size: 11.5px;
  color: var(--dssad-text-dim);
}

.submit {
  width: 100%;
  margin-top: 4px;
  letter-spacing: 4px;
}

.tips {
  margin-top: 22px;
  padding-top: 16px;
  border-top: 1px dashed var(--dssad-border);
}

.tips p {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 0 0 9px;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--dssad-text-dim);
}

.tips p:last-child {
  margin-bottom: 0;
}

.tips code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--dssad-panel-2);
  color: #79c0ff;
  font-family: 'JetBrains Mono', Consolas, monospace;
}
</style>
