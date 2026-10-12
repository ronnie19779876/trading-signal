<script setup lang="ts">
import { computed, onMounted, ref, useTemplateRef } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { selectionApi, type ScoreView } from '../api/selection'
import type { AuditReport } from '../api/account'
import { getUniverse } from '../api/marketdata'
import { auditCheckLabel } from '../lib/audit'
import { errMsg } from '../lib/format'
import RankingTab from '../components/selection/RankingTab.vue'
import ChangesTab from '../components/selection/ChangesTab.vue'
import SectorsTab from '../components/selection/SectorsTab.vue'
import InputsTab from '../components/selection/InputsTab.vue'
import ScoreDrawer from '../components/selection/ScoreDrawer.vue'

/**
 * 量化选股页（§25.9 第 6.3 期）：只读。顶部是评分日的概况与审计（有问题才列），下面四个标签页：
 * 评分榜、评级变动（与上一个评分日比）、板块、数据与审计。点任一行打开详情抽屉。
 * 日期只能选库里有评分的交易日；评级变动的「上一日」也取自评分日列表，而不是日历上的前一天。
 */
const days = ref<string[]>([])
const date = ref<string | null>(null)
const rows = ref<ScoreView[]>([])
const prev = ref<ScoreView[] | null>(null)
const audit = ref<AuditReport | null>(null)
const names = ref(new Map<string, string | null>())
const tab = ref('ranking')
const error = ref<string | null>(null)
const loading = ref(false)
const prevLoading = ref(false)
let loadSeq = 0

const daySet = computed(() => new Set(days.value))
const prevDate = computed(() => {
  if (!date.value) return null
  return days.value.find((d) => d < date.value!) ?? null   // days 按日期倒序
})

const inputsTab = useTemplateRef<{ load: () => void }>('inputsTab')

async function load() {
  loading.value = true
  error.value = null
  try {
    if (!days.value.length) {
      days.value = await selectionApi.days(5000)
      if (!date.value) date.value = days.value[0] ?? null
    }
    if (!date.value) {
      rows.value = []
      prev.value = null
      audit.value = await selectionApi.audit()
      return
    }
    // 上一评分日只给「评级变动」用，单独取、不挡当天（开发库走隧道，一天 494 行约 360 kB）
    const seq = ++loadSeq
    const p = prevDate.value
    prev.value = null
    prevLoading.value = !!p
    if (p) {
      selectionApi.scores(p)
        .then((pr) => { if (seq === loadSeq) prev.value = pr })
        .catch((e) => { if (seq === loadSeq) error.value = '上一评分日取数失败：' + errMsg(e) })
        .finally(() => { if (seq === loadSeq) prevLoading.value = false })
    }
    const [r, a] = await Promise.all([selectionApi.scores(date.value), selectionApi.audit(date.value)])
    if (seq !== loadSeq) return
    rows.value = r
    audit.value = a
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    loading.value = false
  }
}

async function refresh() {
  days.value = []
  await load()
  inputsTab.value?.load()
}

async function pickDate(d: string | null) {
  date.value = d
  await load()
}

const summary = computed(() => (audit.value?.summary ?? {}) as Record<string, number | string | undefined>)
const failed = computed(() => (audit.value?.checks ?? []).filter((c) => !c.ok).map((c) => ({ ...c, label: auditCheckLabel(c.name) })))
const strongBuy = computed(() => rows.value.filter((r) => r.rating === 'STRONG_BUY').length)

const drawer = ref(false)
const opened = ref<ScoreView | null>(null)
function open(row: ScoreView) {
  opened.value = row
  drawer.value = true
}

onMounted(() => {
  void load()
  getUniverse().then((u) => (names.value = new Map(u.map((i) => [i.symbol, i.nameCn ?? i.name])))).catch(() => { /* 中文名只是锦上添花 */ })
})
</script>

<template>
  <div class="page">
    <PageHeader title="量化选股" hint="四因子评分（估值、成长、盈利、动能，模型 F1）：每个交易日美东 18:30 给标普 500 与纳指 100 的普通股打分，板块内排名、强制分布评级。只记录，不触发信号、不下单">
      <template #actions>
        <el-date-picker :model-value="date" type="date" value-format="YYYY-MM-DD" size="small" style="width: 140px" :clearable="false"
                        :disabled-date="(d: Date) => !daySet.has(d.toLocaleDateString('sv-SE'))" @update:model-value="pickDate" />
        <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
      </template>
    </PageHeader>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <el-alert v-if="!loading && !error && !days.length" type="info" show-icon :closable="false"
              title="还没有评分。输入补齐后跑一次重放（POST /api/selection/replay），或等每日 18:30 的评分作业" />

    <section v-if="audit && date" class="panel">
      <div class="panel__head">
        <h3>{{ date }} 概况</h3>
        <el-tag size="small" :type="audit.ok ? 'success' : 'danger'" effect="plain">审计{{ audit.ok ? '通过' : '不通过' }}</el-tag>
        <span class="panel__src">模型 {{ summary.model ?? 'F1' }}<template v-if="prevDate"> · 上一评分日 {{ prevDate }}</template></span>
      </div>
      <div class="kv-grid">
        <div class="kv"><label>打分 / 范围</label><b class="num">{{ summary.scored ?? rows.length }} / {{ summary.targets ?? '—' }}</b></div>
        <div class="kv"><label>评级</label><b class="num">{{ summary.rated ?? '—' }}</b></div>
        <div class="kv"><label>强烈买入</label><b class="num up">{{ strongBuy }}</b></div>
        <div class="kv"><label>排除</label><b class="num">{{ rows.filter((r) => r.exclusion).length }}</b></div>
      </div>
      <div v-if="failed.length" class="issues">
        <div v-for="c in failed" :key="c.name" class="issue" :class="{ 'issue--critical': c.critical }">
          <el-tag size="small" :type="c.critical ? 'danger' : 'warning'">{{ c.critical ? '关键' : '提示' }}</el-tag>
          <b>{{ c.label }}</b><span>{{ c.detail }}</span>
        </div>
      </div>
    </section>

    <el-tabs v-model="tab" v-loading="loading && !rows.length">
      <el-tab-pane label="评分榜" name="ranking" lazy>
        <RankingTab :rows="rows" :names="names" @open="open" />
      </el-tab-pane>
      <el-tab-pane label="评级变动" name="changes" lazy>
        <div v-loading="prevLoading || loading">
          <ChangesTab v-if="!prevLoading && !loading" :rows="rows" :prev="prev" :prev-date="prevDate" :names="names" @open="open" />
        </div>
      </el-tab-pane>
      <el-tab-pane label="板块" name="sectors" lazy>
        <SectorsTab :rows="rows" />
      </el-tab-pane>
      <el-tab-pane label="数据与审计" name="inputs" lazy>
        <InputsTab ref="inputsTab" :audit="audit" />
      </el-tab-pane>
    </el-tabs>

    <ScoreDrawer v-model="drawer" :row="opened" :name="opened ? names.get(opened.symbol) ?? null : null" />
  </div>
</template>

<style scoped>
.up { color: var(--tr-up); }
.issues { margin-top: 10px; display: flex; flex-direction: column; gap: 6px; }
.issue { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; font-size: 13px; }
.issue--critical b { color: var(--el-color-danger); }
</style>
