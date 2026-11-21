<template>
  <div>
    <div class="panel">
      <h2>生成评估数据集（可选择任意历史资产版本复现）</h2>
      <div class="form-grid">
        <div>
          <label>资产版本（检查时点归属固定，复现旧版可验证历史状态）</label>
          <select v-model.number="form.versionId">
            <option v-for="v in versions" :key="v.id" :value="v.id">{{ v.versionNo }} · {{ v.name }}</option>
          </select>
        </div>
        <div>
          <label>风险汇总口径</label>
          <select v-model="form.scoringMode">
            <option value="MAX_SEVERITY">最高严重等级（突出局部失效，可能被边界案例主导）</option>
            <option value="BURDEN">累计负担分（长度×等级×证据，体现广泛劣化，可能稀释短而严重缺陷）</option>
          </select>
        </div>
        <div style="display:flex;align-items:flex-end">
          <button class="btn" @click="generate">生成 / 重新生成</button>
        </div>
      </div>
      <div class="notice info" style="margin-top:12px">
        <b>当前口径公式：</b>{{ form.scoringMode === 'BURDEN'
          ? '负担分 = Σ 缺陷区间长度(m) × 归一等级(1-4) × 证据权重(FULL=1.0 / PARTIAL=0.8 / SPARSE=0.6)，按总分降序排列。'
          : '取管段内经确认缺陷的最高归一等级(1-4)降序，同分时以负担分作为次序；冲突管段不给名次。' }}
      </div>
      <div v-if="message" class="notice" :class="messageType">{{ message }}</div>
    </div>

    <div class="panel">
      <h2>数据集列表</h2>
      <table>
        <thead><tr><th>数据集编码</th><th>资产版本</th><th>口径</th><th>生成时间</th><th>公式说明</th><th></th></tr></thead>
        <tbody>
          <tr v-for="d in datasets" :key="d.id" :style="d.id === selectedId ? 'background:#f0f8f9' : ''">
            <td class="mono">{{ d.datasetCode }}</td>
            <td>{{ versionNo(d.asOfVersionId) }}</td>
            <td><span class="badge" :class="d.scoringMode === 'BURDEN' ? 'teal' : 'blue'">
              {{ d.scoringMode === 'BURDEN' ? '累计负担分' : '最高严重等级' }}
            </span></td>
            <td class="mono">{{ d.generatedAt }}</td>
            <td class="muted" style="max-width:380px">{{ d.formulaNote }}</td>
            <td><button class="btn secondary small" @click="open(d.id)">查看顺位与影响</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <template v-if="detail">
      <div class="panel">
        <h2>管段风险顺位 · {{ detail.dataset.datasetCode }}
          <span class="badge" :class="detail.dataset.scoringMode === 'BURDEN' ? 'teal' : 'blue'">
            {{ detail.dataset.scoringMode === 'BURDEN' ? '累计负担分口径' : '最高严重等级口径' }}
          </span>
        </h2>
        <table>
          <thead>
            <tr><th>顺位</th><th>管段</th><th>状态</th><th>最高等级</th><th>负担分</th><th>入围缺陷</th><th>冲突数</th><th>证据完整度</th><th>顺位解释（可追溯）</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in detail.scores" :key="row.segment.id">
              <td><b style="font-size:15px">{{ row.score.rankNo ?? '—' }}</b></td>
              <td>{{ row.segment.segmentCode }}<div class="muted">{{ row.segment.lengthM }}m / {{ row.segment.material }}</div></td>
              <td>
                <span v-if="row.score.rankingPaused" class="badge red">暂停排序</span>
                <span v-else class="badge green">参与排序</span>
              </td>
              <td>{{ row.score.maxGrade ?? '—' }}</td>
              <td class="mono">{{ row.score.burdenScore }}</td>
              <td>{{ row.score.eligibleClusterCount }}</td>
              <td>{{ row.score.conflictCount }}</td>
              <td>{{ row.score.evidenceCompleteness != null ? (row.score.evidenceCompleteness * 100).toFixed(0) + '%' : '—' }}</td>
              <td style="max-width:460px">
                <div v-if="row.score.pauseReasons" class="notice warn" style="padding:6px 10px;margin-bottom:6px">{{ row.score.pauseReasons }}</div>
                <div class="explain">{{ row.score.explanation }}</div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="panel">
        <h2>图纸修订影响清单（拆分继承 / 跨点隔离 / 历史不搬动）</h2>
        <table>
          <thead><tr><th>类型</th><th>旧管段</th><th>新管段</th><th>继承缺陷数</th><th>隔离数</th><th>里程依据与说明</th></tr></thead>
          <tbody>
            <tr v-for="r in impacts" :key="r.impact.id">
              <td><span class="badge" :class="impactClass(r.impact.impactType)">{{ impactText(r.impact.impactType) }}</span></td>
              <td>{{ r.oldSegment.segmentCode }}</td>
              <td>{{ r.newSegment ? r.newSegment.segmentCode : '—' }}</td>
              <td>{{ r.impact.inheritedCount }}</td>
              <td>
                <span :class="r.impact.quarantinedCount > 0 ? 'badge red' : ''">
                  {{ r.impact.quarantinedCount }}
                </span>
              </td>
              <td class="muted" style="max-width:480px">{{ r.impact.note }}</td>
            </tr>
          </tbody>
        </table>
        <div class="notice info" style="margin-top:12px">
          只有里程映射证实完整落入新段的观察才会继承；跨拆分点的记录被隔离且不会复制到两段；历史归属行始终保留在检查时点版本上。
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import http from '../api/http'

const versions = ref([])
const datasets = ref([])
const form = reactive({ versionId: null, scoringMode: 'MAX_SEVERITY' })
const selectedId = ref(null)
const detail = ref(null)
const impacts = ref([])
const message = ref('')
const messageType = ref('info')

onMounted(async () => {
  const { data } = await http.get('/assets/versions')
  versions.value = data
  form.versionId = data[data.length - 1].id
  await loadDatasets()
})

async function loadDatasets() {
  const { data } = await http.get('/datasets')
  datasets.value = data
  if (selectedId.value && data.some((d) => d.id === selectedId.value)) {
    await open(selectedId.value)
  }
}

async function generate() {
  try {
    const { data } = await http.post('/datasets/generate', { ...form })
    message.value = `已生成数据集 ${data.code}：参与排序 ${data.ranked} 段，暂停 ${data.paused} 段，隔离 ${data.quarantined} 处跨边界缺陷`
    messageType.value = 'ok'
    await loadDatasets()
    const fresh = (await http.get('/datasets')).data
    const target = fresh.find((d) => d.asOfVersionId === form.versionId && d.scoringMode === form.scoringMode)
    if (target) await open(target.id)
  } catch (e) {
    message.value = e.response?.data?.error || '生成失败'
    messageType.value = 'error'
  }
}

async function open(id) {
  selectedId.value = id
  const [s, i] = await Promise.all([
    http.get(`/datasets/${id}/scores`),
    http.get(`/datasets/${id}/impacts`)
  ])
  detail.value = s.data
  impacts.value = i.data.impacts
}

function versionNo(id) { return versions.value.find((v) => v.id === id)?.versionNo || id }
function impactText(t) { return { SPLIT: '拆分', MERGE: '合并', RETAIN: '延续', RETIRE: '退役' }[t] || t }
function impactClass(t) { return { SPLIT: 'amber', MERGE: 'blue', RETAIN: 'green', RETIRE: 'gray' }[t] }
</script>
