<template>
  <div>
    <div class="notice info">
      同一管段、同一缺陷类型且归一区间重叠或间距 ≤0.5m 的观察归为同一缺陷簇：同等级多批次仅计一次；
      等级不相容且原始证据不足以裁决时保留冲突并暂停该管段排序，等待人工复核。
    </div>

    <div class="panel">
      <h2>缺陷聚类（{{ clusters.length }} 个，其中冲突 {{ conflictClusters.length }} 个）</h2>
      <table>
        <thead>
          <tr><th>聚类编号</th><th>管段</th><th>缺陷</th><th>归一区间</th><th>状态</th><th>确定等级</th><th>成员观察</th><th>说明 / 裁决</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in clusters" :key="row.cluster.id">
            <td class="mono">{{ row.cluster.clusterCode }}</td>
            <td>{{ row.segmentCode }}</td>
            <td>{{ defectText(row.cluster.defectType) }}</td>
            <td class="mono">[{{ row.cluster.normStartM }}, {{ row.cluster.normEndM }}]</td>
            <td><span class="badge" :class="statusClass(row.cluster.status)">{{ statusText(row.cluster.status) }}</span></td>
            <td>{{ row.cluster.resolvedGrade ?? '—' }}</td>
            <td>
              <div v-for="m in row.members" :key="m.member.id" class="flex" style="gap:6px">
                <span class="badge" :class="m.member.memberRole === 'PRIMARY' ? 'blue' : 'gray'">
                  {{ m.member.memberRole === 'PRIMARY' ? '主张' : '重复件' }}
                </span>
                <span class="mono">OBS#{{ m.observation.id }}</span>
                <span class="muted">{{ m.batchCode }} 等级{{ m.member.gradeAtInspection }}({{ m.member.evidenceStrength }})</span>
              </div>
            </td>
            <td style="max-width: 320px">
              <div class="muted">{{ row.cluster.resolutionNote }}</div>
              <button v-if="row.cluster.conflictFlag" class="btn small" style="margin-top:6px" @click="openAdj(row.cluster)">
                人工裁决
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="target" class="modal-mask" @click.self="target = null">
      <div class="modal">
        <h3>裁决冲突 {{ target.clusterCode }}</h3>
        <p class="muted">{{ target.resolutionNote }}</p>
        <label>采信的归一等级（1-4）</label>
        <select v-model.number="grade">
          <option :value="1">1（轻微）</option><option :value="2">2（中等）</option>
          <option :value="3">3（严重）</option><option :value="4">4（重大）</option>
        </select>
        <label style="margin-top: 12px">复核说明（记录在案）</label>
        <textarea v-model="note" rows="3" placeholder="例如：复核视频帧 12:31，采信等级 4"></textarea>
        <div class="flex" style="margin-top: 14px">
          <button class="btn" @click="submitAdj">确认裁决并恢复排序</button>
          <button class="btn secondary" @click="target = null">取消</button>
        </div>
        <div v-if="error" class="notice error" style="margin-top: 10px">{{ error }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import http from '../api/http'

const clusters = ref([])
const target = ref(null)
const grade = ref(4)
const note = ref('')
const error = ref('')

const conflictClusters = computed(() => clusters.value.filter((c) => c.cluster.conflictFlag))

onMounted(async () => {
  const { data } = await http.get('/clusters')
  clusters.value = data
})

function openAdj(cluster) {
  target.value = cluster
  grade.value = cluster.resolvedGrade || 4
  note.value = ''
  error.value = ''
}

async function submitAdj() {
  try {
    await http.post(`/clusters/${target.value.id}/adjudicate`, { resolvedGrade: grade.value, note: note.value })
    target.value = null
    const { data } = await http.get('/clusters')
    clusters.value = data
  } catch (e) {
    error.value = e.response?.data?.error || '裁决失败'
  }
}

function defectText(t) { return { CRACK: '裂缝', DEFORMATION: '变形', LEAK: '渗漏', JOINT_DISLOCATION: '接口错位' }[t] || t }
function statusText(s) { return { ACTIVE: '有效', CONFLICT: '冲突暂停', ADJUDICATED: '已裁决', BELOW_RULE: '未入围' }[s] || s }
function statusClass(s) { return { ACTIVE: 'green', CONFLICT: 'red', ADJUDICATED: 'blue', BELOW_RULE: 'gray' }[s] }
</script>
