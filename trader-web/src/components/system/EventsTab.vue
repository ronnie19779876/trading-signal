<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { usePager } from '../../composables/usePager'
import { errMsg, fmtEt } from '../../lib/format'
import { EVENT_LABEL } from '../../lib/system'
import { getEvents, type EventView } from '../../api/gateways'

/** 网关连接事件：最近 500 条，可按券商与事件类型筛，分页。每次部署重启都会留一对"断开 / 连上"。 */
const props = defineProps<{ active: boolean }>()

const events = ref<EventView[]>([])
const broker = ref('')
const kind = ref('')
const loading = ref(false)
const error = ref<string | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    events.value = await getEvents(500)
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    loading.value = false
  }
}
watch(() => props.active, (a) => { if (a) void load() }, { immediate: true })

const BROKER_LABEL: Record<string, string> = { IBKR: '盈透', FUTU: '富途' }
const filtered = computed(() =>
  events.value.filter((e) => (!broker.value || e.broker === broker.value) && (!kind.value || e.event === kind.value)),
)
const { page, pageSize, paged, total } = usePager(filtered, 20, [broker, kind])
</script>

<template>
  <section v-loading="loading" class="panel">
    <div class="panel__head">
      <h3>连接事件</h3>
      <span class="panel__src">最近 500 条，最新的在上；每次部署重启都会留一对"断开 / 连上"</span>
      <span class="panel__grow" />
      <el-radio-group v-model="broker" size="small">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="IBKR">盈透</el-radio-button>
        <el-radio-button value="FUTU">富途</el-radio-button>
      </el-radio-group>
      <el-select v-model="kind" size="small" clearable placeholder="全部事件" style="width: 120px">
        <el-option v-for="(v, k) in EVENT_LABEL" :key="k" :label="v.text" :value="k" />
      </el-select>
      <el-button size="small" @click="load">刷新</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="dtable-wrap">
      <table class="dtable">
        <thead><tr><th>时间</th><th>券商</th><th>事件</th><th>说明</th></tr></thead>
        <tbody>
          <tr v-for="e in paged" :key="e.id">
            <td class="muted-cell">{{ fmtEt(e.occurredAt) }}</td>
            <td>{{ BROKER_LABEL[e.broker] ?? e.broker }}</td>
            <td><el-tag size="small" :type="EVENT_LABEL[e.event]?.type ?? 'info'">{{ EVENT_LABEL[e.event]?.text ?? e.event }}</el-tag></td>
            <td class="wrap">{{ e.detail }}</td>
          </tr>
          <tr v-if="!filtered.length"><td colspan="4" class="empty">{{ loading ? '加载中…' : '没有事件（存储未启用或尚无记录）' }}</td></tr>
        </tbody>
      </table>
    </div>
    <div v-if="total > pageSize" class="pager">
      <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="total" :page-sizes="[20, 50, 100]"
                     layout="total, sizes, prev, pager, next" size="small" background />
    </div>
  </section>
</template>

<style scoped>
.dtable { font-size: 13px; }
.muted-cell { color: var(--el-text-color-secondary); }
.wrap { white-space: normal; }
.pager { display: flex; justify-content: flex-end; margin-top: 10px; }
</style>
