<template>
  <div>
    <div class="notice info">
      判读规则决定哪些确认缺陷进入评估数据集（最低归一等级、最短区间长度、最低证据强度）。
      调整规则后请到「评估数据集与顺位」重新生成数据集，口径变化对使用者全程可见。
    </div>
    <div class="panel">
      <h2>按缺陷类型的入围门槛</h2>
      <table>
        <thead><tr><th>缺陷类型</th><th>最低等级</th><th>最短区间(m)</th><th>最低证据</th><th>启用</th><th></th></tr></thead>
        <tbody>
          <tr v-for="r in rules" :key="r.defectType">
            <td>{{ defectText(r.defectType) }} <span class="mono muted">{{ r.defectType }}</span></td>
            <td><input v-model.number="r.minGrade" type="number" min="1" max="4" style="width:80px" /></td>
            <td><input v-model="r.minLengthM" type="number" step="0.1" style="width:110px" /></td>
            <td>
              <select v-model="r.requiredEvidence" style="width:130px">
                <option>FULL</option><option>PARTIAL</option><option>SPARSE</option>
              </select>
            </td>
            <td><input v-model="r.enabled" type="checkbox" style="width:auto" /></td>
            <td><button class="btn small" @click="save(r)">保存</button></td>
          </tr>
        </tbody>
      </table>
      <div v-if="message" class="notice" :class="messageType" style="margin-top:12px">{{ message }}</div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import http from '../api/http'

const rules = ref([])
const message = ref('')
const messageType = ref('info')

onMounted(async () => {
  const { data } = await http.get('/rules')
  rules.value = data
})

async function save(rule) {
  try {
    await http.put(`/rules/${rule.defectType}`, {
      minGrade: rule.minGrade,
      minLengthM: Number(rule.minLengthM),
      requiredEvidence: rule.requiredEvidence,
      enabled: rule.enabled
    })
    message.value = `规则 ${rule.defectType} 已保存，请重新生成数据集使新门槛生效`
    messageType.value = 'ok'
  } catch (e) {
    message.value = e.response?.data?.error || '保存失败'
    messageType.value = 'error'
  }
}

function defectText(t) { return { CRACK: '裂缝', DEFORMATION: '变形', LEAK: '渗漏', JOINT_DISLOCATION: '接口错位' }[t] || t }
</script>
