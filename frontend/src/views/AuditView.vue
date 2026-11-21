<template>
  <div class="panel">
    <h2>审计日志（最近 100 条）</h2>
    <table>
      <thead><tr><th>时间</th><th>用户</th><th>动作</th><th>对象</th><th>详情</th></tr></thead>
      <tbody>
        <tr v-for="log in logs" :key="log.id">
          <td class="mono">{{ log.createdAt }}</td>
          <td>{{ log.userId || '—' }}</td>
          <td><span class="badge blue">{{ log.action }}</span></td>
          <td class="mono">{{ log.targetRef || '—' }}</td>
          <td class="muted">{{ log.detail || '—' }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import http from '../api/http'

const logs = ref([])
onMounted(async () => {
  const { data } = await http.get('/audit')
  logs.value = data
})
</script>
