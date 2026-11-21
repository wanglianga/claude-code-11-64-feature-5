<template>
  <div>
    <div class="notice info">
      队列统计按<b>材料 × 投用年代 × 管径 × 投用年龄层</b>比较结构缺陷表现，用于调整普查重点，<b>不对单段管道重新排序</b>。
      NAIVE 口径透明但含选择性检查偏差；IPW 按历史受检概率加权校正，需检查权重是否极端。未检查/遮挡区间不计“无缺陷”。
    </div>

    <!-- 运行生成 -->
    <div class="panel">
      <h2>生成队列运行（双口径，结果版本化保留）</h2>
      <div class="form-grid">
        <div>
          <label>估计口径</label>
          <select v-model="form.estimationMethod">
            <option value="NAIVE">NAIVE：已检查长度中的缺陷比例（透明，高风险区过度检查会偏高）</option>
            <option value="IPW">IPW：按受检概率逆概率加权（减轻选择性检查，需查极端权重）</option>
          </select>
        </div>
        <div>
          <label>基准日</label>
          <input v-model="form.asOfDate" type="date" />
        </div>
        <div>
          <label>年龄层宽度（年）</label>
          <input v-model.number="form.ageBandYears" type="number" min="1" max="50" />
        </div>
        <div style="display:flex;align-items:flex-end">
          <button class="btn" @click="generate">生成运行</button>
        </div>
      </div>
      <div v-if="message" class="notice" :class="messageType" style="margin-top:12px">{{ message }}</div>
    </div>

    <!-- 运行列表 -->
    <div class="panel">
      <h2>队列运行（CURRENT 当前有效 / SUPERSEDED 被补录撤销触发的重算取代，结果仍可查）</h2>
      <table>
        <thead>
          <tr><th>运行编码</th><th>口径</th><th>基准日</th><th>状态</th><th>单元格</th><th>低覆盖/极端权重/不可推断</th><th>源版本钉选</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="r in runs" :key="r.id" :style="r.id === selectedRunId ? 'background:#f0f8f9' : ''">
            <td class="mono">{{ r.runCode }}</td>
            <td><span class="badge" :class="r.estimationMethod === 'IPW' ? 'teal' : 'blue'">
              {{ r.estimationMethod === 'IPW' ? 'IPW 加权' : 'NAIVE 直接' }}
            </span></td>
            <td class="mono">{{ r.asOfDate }}</td>
            <td><span class="badge" :class="r.status === 'CURRENT' ? 'green' : 'gray'">{{ r.status }}</span></td>
            <td>{{ runCellCount(r.id) }}</td>
            <td class="mono">{{ runFlags(r.id) }}</td>
            <td class="muted mono" style="font-size:11px">资产#{{ r.assetVersionId }} / 暴露#{{ r.exposureVersionId }} / 缺陷#{{ r.defectVersionId }}</td>
            <td><button class="btn secondary small" @click="openRun(r.id)">查看结果</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <template v-if="detail">
      <!-- 口径说明与不可推断范围 -->
      <div class="panel">
        <h2>{{ detail.run.runCode }} · 口径说明与适用边界</h2>
        <div class="explain" style="margin-bottom:8px"><b>方法：</b>{{ detail.run.methodNote }}</div>
        <div class="explain" style="margin-bottom:8px"><b>选检模型：</b>{{ detail.run.selectionModel }}</div>
        <div class="notice warn" style="margin-bottom:0"><b>不可推断范围：</b>{{ detail.run.nonInferenceNote }}</div>
      </div>

      <!-- 队列单元格 -->
      <div class="panel">
        <h2>年龄分层队列对比（分母 / 权重 / 标记全程可见）</h2>
        <table>
          <thead>
            <tr>
              <th>材料</th><th>投用年代</th><th>管径</th><th>年龄层</th>
              <th>有效覆盖</th><th>缺陷比例<br />(NAIVE)</th><th>加权缺陷比例<br />(IPW)</th>
              <th>复发率</th><th>分母长度(m)<br />总长/受检/遮挡/缺陷</th>
              <th>事件数<br />首现/复发</th><th>权重 min~max</th><th>标记</th><th>证据</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in detail.cells" :key="cellKey(c)">
              <td>{{ c.material }}</td>
              <td>{{ c.commissionDecade }}</td>
              <td>{{ c.diameterBand }}</td>
              <td class="mono">{{ c.ageBand }}</td>
              <td style="min-width:120px">
                <div class="bar"><span :style="{ width: pct(c.effectiveCoverage) }"></span></div>
                <div class="mono muted">{{ pct(c.effectiveCoverage) }}</div>
              </td>
              <td class="mono">{{ fmt(c.defectRatio) }}</td>
              <td class="mono">{{ c.weightedDefectRatio != null ? fmt(c.weightedDefectRatio) : '—' }}</td>
              <td class="mono">{{ c.recurrenceRate != null ? fmt(c.recurrenceRate) : '—' }}</td>
              <td class="mono" style="font-size:11.5px">
                {{ c.totalLengthM }} / {{ c.inspectedLengthM }} / {{ c.obscuredLengthM }} / {{ c.defectiveLengthM }}
              </td>
              <td class="mono">{{ c.firstFindingCount }} / {{ c.recurrenceCount }}</td>
              <td class="mono">{{ c.minWeight != null ? c.minWeight + ' ~ ' + c.maxWeight : '—' }}</td>
              <td>
                <span v-if="c.nonInferableFlag" class="badge red">不可推断</span>
                <span v-if="c.lowCoverageFlag" class="badge amber">低覆盖</span>
                <span v-if="c.extremeWeightFlag" class="badge red">极端权重</span>
                <span v-if="!c.nonInferableFlag && !c.lowCoverageFlag && !c.extremeWeightFlag" class="badge green">正常</span>
              </td>
              <td><button class="btn secondary small" @click="openEvidence(c)">来源({{ evidenceCount(cellKey(c)) }})</button></td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 双运行对比 -->
      <div class="panel">
        <h2>运行对比（同键单元格并排，典型：NAIVE vs IPW）</h2>
        <div class="flex">
          <select v-model.number="compareA" style="width:260px">
            <option v-for="r in runs" :key="r.id" :value="r.id">{{ r.runCode }}（{{ r.estimationMethod }}）</option>
          </select>
          <select v-model.number="compareB" style="width:260px">
            <option v-for="r in runs" :key="r.id" :value="r.id">{{ r.runCode }}（{{ r.estimationMethod }}）</option>
          </select>
          <button class="btn secondary small" @click="doCompare">对比</button>
        </div>
        <table v-if="comparison" style="margin-top:12px">
          <thead><tr><th>队列键</th><th>运行 A 缺陷比例</th><th>运行 B 缺陷比例</th><th>A 覆盖</th><th>B 覆盖</th><th>差异说明</th></tr></thead>
          <tbody>
            <tr v-for="(pair, key) in comparison.cells" :key="key">
              <td class="mono" style="font-size:11.5px">{{ key }}</td>
              <td class="mono">{{ ratioOf(pair, compareA) }}</td>
              <td class="mono">{{ ratioOf(pair, compareB) }}</td>
              <td class="mono">{{ pct(coverageOf(pair, compareA)) }}</td>
              <td class="mono">{{ pct(coverageOf(pair, compareB)) }}</td>
              <td class="muted" style="font-size:11.5px">{{ diffNote(pair, key) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- 源数据与补录/撤销 -->
    <div class="panel">
      <h2>源数据（资产沿革 / 检查暴露 / 缺陷事件，分别版本化）</h2>
      <div class="tabs">
        <button v-for="t in tabs" :key="t" :class="{ active: tab === t }" @click="switchTab(t)">{{ tabName(t) }}</button>
      </div>

      <table v-if="tab === 'history'">
        <thead><tr><th>物理管道</th><th>事件</th><th>材料/管径</th><th>年龄基准年</th><th>区间</th><th>年龄重置</th><th>版本</th><th>证据</th></tr></thead>
        <tbody>
          <tr v-for="h in history" :key="h.id">
            <td>{{ h.physicalPipeCode }}</td>
            <td><span class="badge blue">{{ h.eventType }}</span></td>
            <td>{{ h.material }} / DN{{ h.diameterMm }}</td>
            <td class="mono">{{ h.commissionYear }}</td>
            <td class="mono" style="font-size:11.5px">{{ h.effectiveFrom }} ~ {{ h.effectiveTo || '至今' }}</td>
            <td><span :class="h.ageReset ? 'badge amber' : 'badge gray'">{{ h.ageReset ? '重置' : '延续' }}</span></td>
            <td class="mono">#{{ h.sourceVersionId }}</td>
            <td class="muted" style="font-size:11px">{{ h.evidenceRef }}</td>
          </tr>
        </tbody>
      </table>

      <table v-if="tab === 'exposure'">
        <thead><tr><th>物理管道</th><th>批次</th><th>检查时间</th><th>观察范围(m)</th><th>状态</th><th>风险区</th><th>版本</th><th></th></tr></thead>
        <tbody>
          <tr v-for="e in exposures" :key="e.id">
            <td>{{ e.physicalPipeCode }}</td>
            <td class="mono">{{ e.batchCode }}</td>
            <td class="mono" style="font-size:11.5px">{{ e.inspectedAt }}</td>
            <td class="mono">[{{ e.stationStartM }}, {{ e.stationEndM }}]</td>
            <td>
              <span class="badge" :class="e.status === 'REVOKED' ? 'gray' : (e.coverageStatus === 'OBSCURED' ? 'amber' : 'green')">
                {{ e.status === 'REVOKED' ? '已撤销' : (e.coverageStatus === 'OBSCURED' ? '遮挡:' + e.obscuredReason : '有效观察') }}
              </span>
            </td>
            <td><span :class="e.riskZone === 'HIGH_RISK' ? 'badge red' : 'badge gray'">{{ e.riskZone === 'HIGH_RISK' ? '高风险' : '普通' }}</span></td>
            <td class="mono">#{{ e.sourceVersionId }}</td>
            <td>
              <button v-if="e.status === 'ACTIVE'" class="btn danger small" @click="revoke(e.id)">撤销观察</button>
              <span v-else class="muted">留痕 #{{ e.revokedByVersionId }}</span>
            </td>
          </tr>
        </tbody>
      </table>

      <table v-if="tab === 'event'">
        <thead><tr><th>物理管道</th><th>事件</th><th>类型/等级</th><th>里程(m)</th><th>发现时间</th><th>关联</th><th>维修与修复后状态</th><th>暴露键</th></tr></thead>
        <tbody>
          <tr v-for="d in defectEvents" :key="d.id">
            <td>{{ d.physicalPipeCode }}</td>
            <td><span :class="d.eventKind === 'RECURRENCE' ? 'badge red' : 'badge blue'">
              {{ d.eventKind === 'RECURRENCE' ? '复发' : '首次发现' }}
            </span></td>
            <td>{{ defectText(d.defectType) }} / {{ d.normalizedGrade }}</td>
            <td class="mono">[{{ d.stationStartM }}, {{ d.stationEndM }}]</td>
            <td class="mono" style="font-size:11.5px">{{ d.foundAt }}</td>
            <td class="mono">{{ d.originalEventId ? '原事件#' + d.originalEventId : '—' }}</td>
            <td style="font-size:11.5px">{{ d.repairId ? d.repairMethod + ' → ' + d.postRepairStatus : '未维修' }}</td>
            <td class="mono muted" style="font-size:10.5px">{{ d.exposureKey }}</td>
          </tr>
        </tbody>
      </table>

      <table v-if="tab === 'version'">
        <thead><tr><th>域</th><th>版本号</th><th>说明</th><th>创建时间</th></tr></thead>
        <tbody>
          <tr v-for="v in versions" :key="v.id">
            <td><span class="badge teal">{{ domainText(v.sourceDomain) }}</span></td>
            <td class="mono">{{ v.versionNo }}</td>
            <td class="muted">{{ v.sourceNote }}</td>
            <td class="mono" style="font-size:11.5px">{{ v.createdAt }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 补录换管/内衬 -->
    <div class="panel">
      <h2>补录换管 / 内衬 / 材料变更日期（封口旧区间、年龄重新起算，仅重算受影响队列）</h2>
      <div class="form-grid">
        <div><label>物理管道编码</label><input v-model="backfill.physicalPipeCode" placeholder="如 P-PVC-01" /></div>
        <div><label>事件类型</label>
          <select v-model="backfill.eventType">
            <option>PIPE_REPLACEMENT</option><option>RELINER</option><option>MATERIAL_CHANGE</option>
          </select>
        </div>
        <div><label>生效日期</label><input v-model="backfill.effectiveDate" type="date" /></div>
        <div><label>材料</label><input v-model="backfill.material" /></div>
        <div><label>管径(mm)</label><input v-model.number="backfill.diameterMm" type="number" /></div>
        <div><label>长度(m)</label><input v-model="backfill.lengthM" type="number" step="0.001" /></div>
        <div><label>证据索引</label><input v-model="backfill.evidenceRef" /></div>
        <div><label>说明</label><input v-model="backfill.note" /></div>
        <div style="display:flex;align-items:flex-end"><button class="btn" @click="doBackfill">补录并重算</button></div>
      </div>
    </div>

    <!-- 证据下钻弹窗 -->
    <div v-if="evidenceModal.length" class="modal-mask" @click.self="evidenceModal = []">
      <div class="modal" style="width:720px">
        <h3>证据来源 · {{ evidenceCellKey }}</h3>
        <table>
          <thead><tr><th>证据类型</th><th>来源与明细</th></tr></thead>
          <tbody>
            <tr v-for="(e, i) in evidenceModal" :key="i">
              <td><span class="badge" :class="evidenceClass(e.evidenceKind)">{{ evidenceText(e.evidenceKind) }}</span></td>
              <td class="muted" style="font-size:12px">{{ e.detail }}</td>
            </tr>
          </tbody>
        </table>
        <div style="text-align:right;margin-top:12px"><button class="btn secondary small" @click="evidenceModal = []">关闭</button></div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import http from '../api/http'

const runs = ref([])
const detail = ref(null)
const selectedRunId = ref(null)
const history = ref([])
const exposures = ref([])
const defectEvents = ref([])
const versions = ref([])
const comparison = ref(null)
const compareA = ref(null)
const compareB = ref(null)
const evidenceModal = ref([])
const evidenceCellKey = ref('')
const message = ref('')
const messageType = ref('info')
const tab = ref('history')
const tabs = ['history', 'exposure', 'event', 'version']

const form = reactive({ estimationMethod: 'NAIVE', asOfDate: '2026-11-21', ageBandYears: 10 })
const backfill = reactive({
  physicalPipeCode: '', eventType: 'PIPE_REPLACEMENT', effectiveDate: '',
  material: '', diameterMm: 800, lengthM: '', evidenceRef: '', note: ''
})

onMounted(async () => {
  await loadRuns()
  const [h, e, d] = await Promise.all([
    http.get('/cohorts/asset-history'),
    http.get('/cohorts/exposures'),
    http.get('/cohorts/defect-events')
  ])
  history.value = h.data
  exposures.value = e.data
  defectEvents.value = d.data
  await loadVersions()
  if (runs.value.length) {
    compareA.value = runs.value[runs.value.length - 1].id
    compareB.value = runs.value[0].id
  }
})

async function loadRuns() {
  const { data } = await http.get('/cohorts/runs')
  runs.value = data
}

async function loadVersions() {
  const domains = ['ASSET_HISTORY', 'INSPECTION_EXPOSURE', 'DEFECT_EVENT', 'COHORT_RUN']
  const all = await Promise.all(domains.map((d) => http.get('/cohorts/versions', { params: { domain: d } })))
  versions.value = all.flatMap((r) => r.data)
}

async function generate() {
  try {
    const { data } = await http.post('/cohorts/runs', { ...form })
    message.value = `已生成 ${data.code}：单元格 ${data.cells}，低覆盖 ${data.lowCoverageCells}，极端权重 ${data.extremeWeightCells}，不可推断 ${data.nonInferableCells}`
    messageType.value = 'ok'
    await loadRuns()
    await openRun(data.runId)
  } catch (e) {
    message.value = e.response?.data?.error || '生成失败'
    messageType.value = 'error'
  }
}

async function openRun(id) {
  selectedRunId.value = id
  const { data } = await http.get(`/cohorts/runs/${id}`)
  detail.value = data
}

function cellKey(c) {
  return `${c.material}|${c.commissionDecade}|${c.diameterBand}|${c.ageBand}`
}

function runCellCount(id) {
  return id === selectedRunId.value && detail.value ? detail.value.cells.length : ''
}

function runFlags(id) {
  if (id !== selectedRunId.value || !detail.value) return ''
  const cells = detail.value.cells
  const low = cells.filter((c) => c.lowCoverageFlag).length
  const extreme = cells.filter((c) => c.extremeWeightFlag).length
  const non = cells.filter((c) => c.nonInferableFlag).length
  return `${low} / ${extreme} / ${non}`
}

function evidenceCount(key) {
  if (!detail.value) return 0
  return detail.value.evidence.filter((e) => e.cellKey === key).length
}

async function openEvidence(cell) {
  const key = cellKey(cell)
  evidenceCellKey.value = key
  const { data } = await http.get(`/cohorts/runs/${detail.value.run.id}/evidence`, { params: { cellKey: key } })
  evidenceModal.value = data
}

async function doCompare() {
  if (!compareA.value || !compareB.value) return
  const { data } = await http.post('/cohorts/compare', { runIds: [compareA.value, compareB.value] })
  comparison.value = data
}

function ratioOf(pair, runId) {
  const found = pair.find((p) => p.runId === runId)
  if (!found) return '—'
  const c = found.cell
  return c.weightedDefectRatio != null ? fmt(c.weightedDefectRatio) : fmt(c.defectRatio)
}

function coverageOf(pair, runId) {
  const found = pair.find((p) => p.runId === runId)
  return found ? found.cell.effectiveCoverage : 0
}

function diffNote(pair, key) {
  if (pair.length < 2) return '仅一个运行覆盖该队列键'
  const vals = pair.map((p) => p.cell.weightedDefectRatio ?? p.cell.defectRatio)
  if (vals.some((v) => v == null)) return '存在不可推断单元格（有效覆盖为 0），差异不可解释为材料劣化'
  const delta = vals[1] - vals[0]
  return Math.abs(delta) < 1e-6 ? '两口径一致' : `差异 ${delta > 0 ? '+' : ''}${delta.toFixed(6)}，差异来自选检加权与覆盖变化`
}

async function switchTab(t) {
  tab.value = t
  if (t === 'version') await loadVersions()
  if (t === 'exposure') exposures.value = (await http.get('/cohorts/exposures')).data
}

async function revoke(id) {
  const reason = prompt('请输入撤销原因')
  if (!reason) return
  const { data } = await http.post(`/cohorts/exposures/${id}/revoke`, { reason })
  message.value = `观察 #${id} 已撤销并重算 ${data.rerunRuns.length} 个受影响队列，旧运行保留为 SUPERSEDED`
  messageType.value = 'ok'
  exposures.value = (await http.get('/cohorts/exposures')).data
  await loadRuns()
  await loadVersions()
}

async function doBackfill() {
  try {
    const { data } = await http.post('/cohorts/backfill-replacement', { ...backfill })
    message.value = `补录完成：新区间 #${data.newHistoryId}，旧区间 #${data.closedHistoryId} 封口，重算 ${data.rerunRuns.length} 个队列`
    messageType.value = 'ok'
    history.value = (await http.get('/cohorts/asset-history')).data
    await loadRuns()
    await loadVersions()
  } catch (e) {
    message.value = e.response?.data?.error || '补录失败'
    messageType.value = 'error'
  }
}

function fmt(v) {
  return v == null ? '不可推断' : Number(v).toFixed(6).replace(/0+$/, '').replace(/\.$/, '')
}
function pct(v) {
  return v == null ? '—' : (Number(v) * 100).toFixed(1) + '%'
}
function defectText(t) {
  return { CRACK: '裂缝', DEFORMATION: '变形', LEAK: '渗漏', JOINT_DISLOCATION: '接口错位' }[t] || t
}
function domainText(d) {
  return { ASSET_HISTORY: '资产沿革', INSPECTION_EXPOSURE: '检查暴露', DEFECT_EVENT: '缺陷事件', COHORT_RUN: '队列运行' }[d] || d
}
function tabName(t) {
  return { history: '资产沿革', exposure: '检查暴露', event: '缺陷事件', version: '源版本' }[t]
}
function evidenceText(k) {
  return {
    ASSET_INTERVAL: '资产区间', INSPECTION_WINDOW: '检查窗', OBSCURED_WINDOW: '遮挡窗',
    FIRST_FINDING: '首次发现', RECURRENCE: '复发', REPAIR: '维修'
  }[k] || k
}
function evidenceClass(k) {
  return {
    ASSET_INTERVAL: 'blue', INSPECTION_WINDOW: 'green', OBSCURED_WINDOW: 'amber',
    FIRST_FINDING: 'teal', RECURRENCE: 'red', REPAIR: 'gray'
  }[k] || 'gray'
}
</script>
