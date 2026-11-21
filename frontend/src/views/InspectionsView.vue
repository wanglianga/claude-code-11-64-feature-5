<template>
  <div>
    <div class="panel">
      <h2>承包商批次（里程起点、方向与分级标准差异在此留痕）</h2>
      <table>
        <thead><tr><th>批次号</th><th>承包商</th><th>检查时间</th><th>分级标准</th><th>方向约定</th><th>内容哈希</th></tr></thead>
        <tbody>
          <tr v-for="b in batches" :key="b.id">
            <td><b>{{ b.batchCode }}</b></td>
            <td>{{ b.contractor }}</td>
            <td class="mono">{{ b.inspectedAt }}</td>
            <td><span class="badge blue">{{ b.gradingStandard }}</span></td>
            <td>{{ b.directionConvention }}</td>
            <td class="mono muted">{{ b.contentHash }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="panel">
      <h2>视频索引（重复上传 / 重叠片段识别）</h2>
      <table>
        <thead><tr><th>视频编号</th><th>批次</th><th>指向管段</th><th>原始里程</th><th>里程方向</th><th>内容哈希</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="v in videos" :key="v.id">
            <td>{{ v.videoCode }}</td>
            <td>{{ batchCode(v.batchId) }}</td>
            <td>{{ v.segmentHint }}</td>
            <td class="mono">[{{ v.startM }}, {{ v.endM }}]</td>
            <td>
              <span class="badge" :class="v.stationDirection === 'REVERSED' ? 'amber' : 'teal'">
                {{ v.stationDirection === 'REVERSED' ? '反向里程(0在下游井)' : '同向' }}
              </span>
            </td>
            <td class="mono muted">{{ v.contentHash }}</td>
            <td>
              <span v-if="v.duplicateOfId" class="badge red">重复视频 → #{{ v.duplicateOfId }}</span>
              <span v-else class="badge green">原始</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="panel">
      <h2>观察与位置对齐（点击“对齐依据”查看候选与归一里程）</h2>
      <table>
        <thead>
          <tr><th>#</th><th>缺陷类型</th><th>原始等级</th><th>原始里程</th><th>证据</th><th>状态</th><th>固定归属</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="o in observations" :key="o.id">
            <td>{{ o.id }}</td>
            <td>{{ defectText(o.defectType) }}</td>
            <td><span class="badge gray">{{ o.rawGrade }}</span></td>
            <td class="mono">[{{ o.stationStartM }}, {{ o.stationEndM }}]</td>
            <td><span class="badge" :class="evClass(o.evidenceStrength)">{{ o.evidenceStrength }}</span></td>
            <td><span class="badge" :class="obsStatusClass(o.status)">{{ obsStatusText(o.status) }}</span></td>
            <td>
              <template v-if="attribs[o.id]">
                管段#{{ attribs[o.id].segmentId }} 归一 <span class="mono">[{{ attribs[o.id].normStartM }}, {{ attribs[o.id].normEndM }}]</span>
                <div class="muted">固定于版本#{{ attribs[o.id].assetVersionId }}</div>
              </template>
              <span v-else class="muted">重复件，不参与对齐</span>
            </td>
            <td>
              <button v-if="attribs[o.id]" class="btn secondary small" @click="showCandidates(o.id)">对齐依据</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="panel">
      <h2>归档上传（重复内容哈希将被拒绝或标记，防止同一观察重复累加）</h2>
      <div class="tabs">
        <button :class="tab === 'batch' ? 'active' : ''" @click="tab = 'batch'">上传批次</button>
        <button :class="tab === 'video' ? 'active' : ''" @click="tab = 'video'">上传视频</button>
        <button :class="tab === 'obs' ? 'active' : ''" @click="tab = 'obs'">登记观察</button>
      </div>

      <div v-if="tab === 'batch'" class="form-grid">
        <div><label>批次号</label><input v-model="batchForm.batchCode" placeholder="B-2026-04" /></div>
        <div><label>承包商</label><input v-model="batchForm.contractor" /></div>
        <div><label>检查时间</label><input v-model="batchForm.inspectedAt" type="datetime-local" /></div>
        <div><label>分级标准</label>
          <select v-model="batchForm.gradingStandard">
            <option>STD-CCT-2021</option><option>STD-DX-2023</option>
          </select>
        </div>
        <div><label>方向约定</label><input v-model="batchForm.directionConvention" /></div>
        <div><label>内容哈希</label><input v-model="batchForm.contentHash" placeholder="试试 hash:batch:b202501 触发重复拦截" /></div>
      </div>
      <div v-if="tab === 'video'" class="form-grid">
        <div><label>批次 ID</label><input v-model.number="videoForm.batchId" type="number" /></div>
        <div><label>视频编号</label><input v-model="videoForm.videoCode" /></div>
        <div><label>文件 URI</label><input v-model="videoForm.fileUri" /></div>
        <div><label>指向管段</label><input v-model="videoForm.segmentHint" placeholder="WS-001" /></div>
        <div><label>起始里程</label><input v-model="videoForm.startM" type="number" step="0.1" /></div>
        <div><label>结束里程</label><input v-model="videoForm.endM" type="number" step="0.1" /></div>
        <div><label>里程方向</label><select v-model="videoForm.stationDirection"><option>FORWARD</option><option>REVERSED</option></select></div>
        <div><label>内容哈希</label><input v-model="videoForm.contentHash" placeholder="试试 sha256:vid-ws001-full 触发重复标记" /></div>
      </div>
      <div v-if="tab === 'obs'" class="form-grid">
        <div><label>视频 ID</label><input v-model.number="obsForm.videoId" type="number" /></div>
        <div><label>缺陷类型</label>
          <select v-model="obsForm.defectType">
            <option>CRACK</option><option>DEFORMATION</option><option>LEAK</option><option>JOINT_DISLOCATION</option>
          </select>
        </div>
        <div><label>原始等级</label><input v-model="obsForm.rawGrade" placeholder="II / B" /></div>
        <div><label>里程起</label><input v-model="obsForm.stationStartM" type="number" step="0.1" /></div>
        <div><label>里程止</label><input v-model="obsForm.stationEndM" type="number" step="0.1" /></div>
        <div><label>证据强度</label><select v-model="obsForm.evidenceStrength"><option>FULL</option><option>PARTIAL</option><option>SPARSE</option></select></div>
        <div><label>证据 URI</label><input v-model="obsForm.evidenceUri" /></div>
      </div>

      <div style="margin-top: 14px" class="flex">
        <button class="btn" @click="submitUpload">提交</button>
        <button class="btn secondary" @click="rebuild">重跑 对齐 → 聚类 流水线</button>
      </div>
      <div v-if="message" class="notice" :class="messageType" style="margin-top: 12px">{{ message }}</div>
      <div v-for="(w, i) in warnings" :key="i" class="notice warn" style="margin-top: 8px">{{ w }}</div>
    </div>

    <div v-if="candidateData" class="modal-mask" @click.self="candidateData = null">
      <div class="modal">
        <h3>观察 #{{ candidateObsId }} 的对齐候选与依据</h3>
        <table>
          <thead><tr><th>候选管段</th><th>版本</th><th>归一区间</th><th>评分</th><th>是否选中</th><th>依据</th></tr></thead>
          <tbody>
            <tr v-for="c in candidateData" :key="c.candidate.id">
              <td>{{ c.segmentCode }}</td>
              <td>{{ c.versionNo }}</td>
              <td class="mono">[{{ c.candidate.normStartM }}, {{ c.candidate.normEndM }}]</td>
              <td>{{ c.candidate.score }}</td>
              <td><span class="badge" :class="c.candidate.selected ? 'green' : 'gray'">{{ c.candidate.selected ? '固定归属' : '落选参照' }}</span></td>
              <td class="muted" style="min-width: 260px">{{ c.candidate.reason }}</td>
            </tr>
          </tbody>
        </table>
        <div class="notice info" style="margin-top: 12px">
          归属固定在“检查发生时有效版本”的管段上；后续测绘修订版本仅作为落选参照保留，历史缺陷不会被静默搬动。
        </div>
        <button class="btn secondary" @click="candidateData = null">关闭</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import http from '../api/http'

const batches = ref([])
const videos = ref([])
const observations = ref([])
const attribs = ref({})
const tab = ref('batch')
const message = ref('')
const messageType = ref('info')
const warnings = ref([])
const candidateData = ref(null)
const candidateObsId = ref(null)

const batchForm = reactive({ batchCode: '', contractor: '', inspectedAt: '', gradingStandard: 'STD-CCT-2021', directionConvention: '上游井起算', contentHash: '' })
const videoForm = reactive({ batchId: 1, videoCode: '', fileUri: 'archive://new.mp4', segmentHint: 'WS-001', startM: 0, endM: 60, stationDirection: 'FORWARD', contentHash: '' })
const obsForm = reactive({ videoId: 1, defectType: 'CRACK', rawGrade: '', stationStartM: 0, stationEndM: 1, evidenceStrength: 'FULL', evidenceUri: 'archive://new' })

onMounted(load)

async function load() {
  const [b, v, o] = await Promise.all([
    http.get('/inspections/batches'),
    http.get('/inspections/videos'),
    http.get('/inspections/observations')
  ])
  batches.value = b.data
  videos.value = v.data
  observations.value = o.data
  const attrs = await Promise.all(
    o.data.filter((x) => x.status !== 'DUPLICATE').map((x) =>
      http.get(`/inspections/observations/${x.id}/attribution`).then((r) => [x.id, r.data]))
  )
  attribs.value = Object.fromEntries(attrs.filter(([, val]) => val))
}

async function showCandidates(id) {
  candidateObsId.value = id
  const { data } = await http.get(`/inspections/observations/${id}/candidates`)
  candidateData.value = data
}

async function submitUpload() {
  message.value = ''
  warnings.value = []
  try {
    if (tab.value === 'batch') {
      const payload = { ...batchForm, inspectedAt: batchForm.inspectedAt.replace('T', ' ') + ':00' }
      const { data } = await http.post('/inspections/batches', payload)
      message.value = `批次已登记：${data.batchCode}（id=${data.batchId}）`
      messageType.value = 'ok'
    } else if (tab.value === 'video') {
      const { data } = await http.post('/inspections/videos', videoForm)
      message.value = data.duplicate
        ? `视频已登记为重复件（duplicateOfId=${data.duplicateOfId}），其观察不会参与风险累加`
        : '视频已登记'
      messageType.value = data.duplicate ? 'warn' : 'ok'
      warnings.value = data.warnings || []
    } else {
      const { data } = await http.post('/inspections/observations', obsForm)
      message.value = `观察已登记：id=${data.observationId}`
      messageType.value = 'ok'
    }
    await load()
  } catch (e) {
    message.value = e.response?.data?.error || '提交失败'
    messageType.value = 'error'
  }
}

async function rebuild() {
  const { data } = await http.post('/inspections/rebuild')
  message.value = `流水线完成：对齐 ${data.alignment.aligned} 条、聚类 ${data.clustering.clusters} 个、冲突 ${data.clustering.conflicts} 个`
  messageType.value = 'ok'
  await load()
}

function batchCode(id) { return batches.value.find((b) => b.id === id)?.batchCode || id }
function defectText(t) { return { CRACK: '裂缝', DEFORMATION: '变形', LEAK: '渗漏', JOINT_DISLOCATION: '接口错位' }[t] || t }
function evClass(e) { return { FULL: 'green', PARTIAL: 'amber', SPARSE: 'gray' }[e] }
function obsStatusText(s) { return { PENDING: '待处理', CONFIRMED: '已确认', DUPLICATE: '重复件', QUARANTINED: '隔离' }[s] || s }
function obsStatusClass(s) { return { PENDING: 'gray', CONFIRMED: 'green', DUPLICATE: 'red', QUARANTINED: 'amber' }[s] }
</script>
