<template>
  <div>
    <div v-if="conflictCount > 0" class="notice warn">
      存在 <b>{{ conflictCount }}</b> 处等级冲突未裁决，相关管段已暂停确定排序，请前往「缺陷聚类与冲突」复核原始证据。
    </div>

    <div class="stat-grid">
      <div class="stat"><div class="num">{{ stats.versions }}</div><div class="lbl">资产版本</div></div>
      <div class="stat"><div class="num">{{ stats.segments }}</div><div class="lbl">当前版管段</div></div>
      <div class="stat"><div class="num">{{ stats.batches }}</div><div class="lbl">承包商检测批次</div></div>
      <div class="stat"><div class="num">{{ stats.videos }}</div><div class="lbl">视频索引（含重复件）</div></div>
      <div class="stat"><div class="num">{{ stats.observations }}</div><div class="lbl">观察总数</div></div>
      <div class="stat"><div class="num">{{ stats.clusters }}</div><div class="lbl">缺陷聚类</div></div>
      <div class="stat"><div class="num" style="color: var(--danger)">{{ stats.conflicts }}</div><div class="lbl">冲突（暂停排序）</div></div>
      <div class="stat"><div class="num">{{ stats.datasets }}</div><div class="lbl">评估数据集</div></div>
    </div>

    <div class="panel">
      <h2>最新数据集风险顺位（口径：{{ latestDataset?.scoringMode === 'BURDEN' ? '累计负担分' : '最高严重等级' }}）</h2>
      <div class="muted" style="margin-bottom: 10px">{{ latestDataset?.formulaNote }}</div>
      <table>
        <thead>
          <tr><th>顺位</th><th>管段</th><th>状态</th><th>最高等级</th><th>负担分</th><th>入围缺陷</th><th>证据完整度</th><th>解释</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in ranking" :key="row.segment.id">
            <td><b>{{ row.score.rankNo || '—' }}</b></td>
            <td>{{ row.segment.segmentCode }}<div class="muted">{{ row.segment.material }} / DN{{ row.segment.diameterMm }}</div></td>
            <td>
              <span v-if="row.score.rankingPaused" class="badge red">暂停排序</span>
              <span v-else class="badge green">已排序</span>
            </td>
            <td>{{ row.score.maxGrade ?? '—' }}</td>
            <td class="mono">{{ row.score.burdenScore }}</td>
            <td>{{ row.score.eligibleClusterCount }}</td>
            <td>{{ row.score.evidenceCompleteness != null ? (row.score.evidenceCompleteness * 100).toFixed(0) + '%' : '—' }}</td>
            <td style="max-width: 420px"><div class="explain">{{ row.score.explanation }}</div></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import http from '../api/http'

const stats = reactive({ versions: 0, segments: 0, batches: 0, videos: 0, observations: 0, clusters: 0, conflicts: 0, datasets: 0 })
const ranking = ref([])
const latestDataset = ref(null)

onMounted(async () => {
  const [versions, batches, videos, observations, clusters, datasets] = await Promise.all([
    http.get('/assets/versions'),
    http.get('/inspections/batches'),
    http.get('/inspections/videos'),
    http.get('/inspections/observations'),
    http.get('/clusters'),
    http.get('/datasets')
  ])
  stats.versions = versions.data.length
  stats.batches = batches.data.length
  stats.videos = videos.data.length
  stats.observations = observations.data.length
  stats.clusters = clusters.data.length
  stats.conflicts = clusters.data.filter((c) => c.cluster.conflictFlag).length
  stats.datasets = datasets.data.length
  const currentVersion = versions.data[versions.data.length - 1]
  const segResp = await http.get(`/assets/versions/${currentVersion.id}/segments`)
  stats.segments = segResp.data.segments.length

  const maxDs = datasets.data.filter((d) => d.scoringMode === 'MAX_SEVERITY').pop()
  if (maxDs) {
    const scoreResp = await http.get(`/datasets/${maxDs.id}/scores`)
    latestDataset.value = scoreResp.data.dataset
    ranking.value = scoreResp.data.scores
  }
})
const conflictCount = computed(() => stats.conflicts)
</script>
