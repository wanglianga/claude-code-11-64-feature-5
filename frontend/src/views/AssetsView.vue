<template>
  <div>
    <div class="panel">
      <h2>资产版本谱系（观察归属按检查发生时的有效版本固定）</h2>
      <table>
        <thead><tr><th>版本号</th><th>名称</th><th>生效时间</th><th>状态</th><th>说明</th></tr></thead>
        <tbody>
          <tr v-for="v in versions" :key="v.id" :style="v.id === selectedVersion ? 'background:#f0f8f9' : ''">
            <td><a href="#" @click.prevent="select(v.id)">{{ v.versionNo }}</a></td>
            <td>{{ v.name }}</td>
            <td class="mono">{{ v.effectiveAt }}</td>
            <td><span class="badge" :class="v.status === 'ACTIVE' ? 'green' : 'gray'">{{ statusText(v.status) }}</span></td>
            <td class="muted">{{ v.note }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="panel">
      <h2>版本 {{ current?.version?.versionNo }} 的井与管段</h2>
      <h3>管段几何</h3>
      <table>
        <thead><tr><th>管段编码</th><th>上游井</th><th>下游井</th><th>长度(m)</th><th>流向</th><th>材质</th><th>管径</th><th>生命周期</th><th>父管段</th></tr></thead>
        <tbody>
          <tr v-for="s in current?.segments || []" :key="s.id">
            <td><b>{{ s.segmentCode }}</b></td>
            <td>{{ manholeCode(s.upstreamMhId) }}</td>
            <td>{{ manholeCode(s.downstreamMhId) }}</td>
            <td class="mono">{{ s.lengthM }}</td>
            <td>{{ s.flowDirection === 'UP_TO_DOWN' ? '上游→下游' : '下游→上游' }}</td>
            <td>{{ s.material }}</td>
            <td>DN{{ s.diameterMm }}</td>
            <td><span class="badge" :class="lcClass(s.lifecycleStatus)">{{ lcText(s.lifecycleStatus) }}</span></td>
            <td>{{ s.parentSegmentId ? '#' + s.parentSegmentId : '—' }}</td>
          </tr>
        </tbody>
      </table>
      <h3>井坐标</h3>
      <table>
        <thead><tr><th>井编号</th><th>X</th><th>Y</th></tr></thead>
        <tbody>
          <tr v-for="m in current?.manholes || []" :key="m.id">
            <td>{{ m.manholeCode }}</td><td class="mono">{{ m.geomX }}</td><td class="mono">{{ m.geomY }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="panel">
      <h2>新旧管段里程映射（测绘修订只形成映射，不搬动历史缺陷）</h2>
      <table>
        <thead><tr><th>旧管段</th><th>旧里程区间</th><th>新管段</th><th>新里程区间</th><th>映射依据</th><th>说明</th></tr></thead>
        <tbody>
          <tr v-for="m in mappings" :key="m.id">
            <td>{{ segCode(m.oldSegmentId) }}</td>
            <td class="mono">[{{ m.oldStartM }}, {{ m.oldEndM }}]</td>
            <td>{{ segCode(m.newSegmentId) }}</td>
            <td class="mono">[{{ m.newStartM }}, {{ m.newEndM }}]</td>
            <td><span class="badge teal">{{ basisText(m.basis) }}</span></td>
            <td class="muted">{{ m.note }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import http from '../api/http'

const versions = ref([])
const current = ref(null)
const mappings = ref([])
const segMap = ref({})

onMounted(async () => {
  const { data } = await http.get('/assets/versions')
  versions.value = data
  const mapResp = await http.get('/assets/mappings')
  mappings.value = mapResp.data
  const segIds = new Set()
  mapResp.data.forEach((m) => { segIds.add(m.oldSegmentId); segIds.add(m.newSegmentId) })
  const entries = await Promise.all([...segIds].map((id) => http.get(`/assets/segments/${id}`)))
  entries.forEach((r) => { segMap.value[r.data.segment.id] = r.data.segment.segmentCode })
  await select(data[data.length - 1].id)
})

async function select(id) {
  const { data } = await http.get(`/assets/versions/${id}/segments`)
  current.value = data
}
function manholeCode(id) {
  return current.value?.manholes.find((m) => m.id === id)?.manholeCode || '#' + id
}
function segCode(id) { return segMap.value[id] || '#' + id }
function statusText(s) { return { ACTIVE: '有效', SUPERSEDED: '已被替代', DRAFT: '草稿' }[s] || s }
function lcText(s) { return { ACTIVE: '在用', SPLIT: '已拆分', MERGED: '已合并', RETIRED: '退役' }[s] || s }
function lcClass(s) { return s === 'SPLIT' ? 'amber' : s === 'RETIRED' ? 'gray' : 'green' }
function basisText(b) {
  return { STATION_EQUIVALENCE: '里程等值', SURVEY_MEASURE: '实测换算', AS_BUILT_CLIP: '竣工裁切' }[b] || b
}
</script>
