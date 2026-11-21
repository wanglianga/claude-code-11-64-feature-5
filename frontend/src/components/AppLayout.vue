<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="logo">排水缺陷普查评估</div>
      <div class="subtitle">结构缺陷可追溯数据平台</div>
      <nav>
        <router-link to="/dashboard">总览</router-link>
        <router-link to="/assets">资产版本与图谱</router-link>
        <router-link to="/inspections">检测归档与对齐</router-link>
        <router-link to="/clusters">缺陷聚类与冲突</router-link>
        <router-link to="/datasets">评估数据集与顺位</router-link>
        <router-link to="/cohorts">材料年代队列统计</router-link>
        <router-link to="/rules">判读规则</router-link>
        <router-link to="/audit">审计日志</router-link>
      </nav>
    </aside>
    <div class="main">
      <header class="topbar">
        <div class="title">{{ pageTitle }}</div>
        <div class="flex">
          <span class="muted">{{ auth.displayName }}（{{ auth.user?.role }}）</span>
          <button class="btn secondary small" @click="doLogout">退出登录</button>
        </div>
      </header>
      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const titles = {
  dashboard: '普查评估总览',
  assets: '资产资料：井、管段与有效版本',
  inspections: '检测归档：视频索引、观察与位置对齐',
  clusters: '缺陷聚类：去重、冲突与人工裁决',
  datasets: '评估数据集：风险顺位与历史复现',
  cohorts: '材料/年代队列：年龄分层缺陷比例、复发率与有效检查覆盖',
  rules: '判读规则（入围门槛）',
  audit: '审计日志'
}
const pageTitle = computed(() => titles[route.name] || '')

async function doLogout() {
  await auth.logout()
  router.push({ name: 'login' })
}
</script>
