<template>
  <div class="login-wrap">
    <div class="login-card">
      <h1>排水管道结构缺陷普查评估平台</h1>
      <div class="hint">机器人检测 · 人工复核 · 竣工资产图 · 历次修复资料的可追溯评估</div>
      <div class="field">
        <label>用户名</label>
        <input v-model="username" placeholder="admin / engineer" @keyup.enter="submit" />
      </div>
      <div class="field">
        <label>密码</label>
        <input v-model="password" type="password" placeholder="请输入密码" @keyup.enter="submit" />
      </div>
      <button class="btn" style="width: 100%" :disabled="loading" @click="submit">
        {{ loading ? '登录中…' : '登 录' }}
      </button>
      <div class="err">{{ error }}</div>
      <div class="accounts">
        <div><b>测试账号</b></div>
        <div>管理员：<code>admin / admin123</code></div>
        <div>复核工程师：<code>engineer / eng123</code></div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const username = ref('engineer')
const password = ref('eng123')
const error = ref('')
const loading = ref(false)
const auth = useAuthStore()
const router = useRouter()

async function submit() {
  error.value = ''
  loading.value = true
  try {
    await auth.login(username.value.trim(), password.value)
    router.push({ name: 'dashboard' })
  } catch (e) {
    error.value = e.response?.data?.error || '登录失败，请检查服务是否启动'
  } finally {
    loading.value = false
  }
}
</script>
