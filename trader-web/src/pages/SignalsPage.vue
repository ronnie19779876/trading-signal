<script setup lang="ts">
import { computed, onMounted, ref, useTemplateRef } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { signalsApi } from '../api/signals'
import type { AuditReport } from '../api/account'
import { aiApi, type AiSettings, type DailyUsage } from '../api/ai'
import { getUniverse, type InstrumentView } from '../api/marketdata'
import TodayTab from '../components/signals/TodayTab.vue'
import SignalsTab from '../components/signals/SignalsTab.vue'
import LedgerTab from '../components/signals/LedgerTab.vue'
import ReplayTab from '../components/signals/ReplayTab.vue'
import { AI_STATUS_LABEL, errMsg, iso, todayEt } from '../components/signals/format'
import type { AiStatus } from '../api/ai'
import { auditCheckLabel } from '../lib/audit'

/**
 * 入场信号页：顶部是评估日的概况与审计（有问题才显示），下面四个标签页：当日、信号、纸面账本、回放。
 * 标的列表只取一次，给各标签页显示中文名与行业。
 */
const audit = ref<AuditReport | null>(null)
const settings = ref<AiSettings | null>(null)
const today = ref<DailyUsage | null>(null)
const universe = ref<InstrumentView[]>([])
const date = ref<string | null>(null)
const tab = ref('today')
const error = ref<string | null>(null)
const loading = ref(false)

// 刷新调各 tab 自己的 load()，而不是换 :key 整体重挂载——后者会丢掉筛选条件和滚动位置。
// lazy 的 tab 没打开过就没挂载，ref 为 null，跳过即可（打开时自己会加载）。
const todayTab = useTemplateRef<{ load: () => void }>('todayTab')
const signalsTab = useTemplateRef<{ load: () => void }>('signalsTab')
const ledgerTab = useTemplateRef<{ load: () => void }>('ledgerTab')

async function load() {
  loading.value = true
  error.value = null
  try {
    const [a, u] = await Promise.all([signalsApi.audit(date.value ?? undefined), aiApi.usage(iso(new Date(Date.now() - 86_400_000)), iso(new Date()))])
    audit.value = a
    if (!date.value) date.value = a.date
    settings.value = u.settings
    today.value = u.days[0] ?? null
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    loading.value = false
  }
}

async function refresh() {
  await load()
  todayTab.value?.load()
  signalsTab.value?.load()
  ledgerTab.value?.load()
}

async function pickDate(d: string | null) {
  date.value = d
  await load()
}

const summary = computed(() => (audit.value?.summary ?? {}) as Record<string, number | boolean | undefined>)
const callsToday = computed(() => (today.value?.day === todayEt() ? today.value.calls : 0))

// ---- 审计：只列没通过的项，名称与原因都转成中文；涉及的标的按原因分组收起 ----
/** 样本形如 "HUM SKIPPED_BUDGET：今天已调用 20 次，达到每日上限 20"：按"状态 + 原因"分组，状态转中文。 */
function groupSamples(samples: string[]) {
  const groups = new Map<string, string[]>()
  for (const raw of samples) {
    const m = /^(\S+)\s+([A-Z_]+)[：:]\s*(.*)$/.exec(raw)
    const key = m ? `${AI_STATUS_LABEL[m[2] as AiStatus] ?? m[2]}：${m[3]}` : '其他'
    const sym = m ? m[1] : raw
    groups.set(key, [...(groups.get(key) ?? []), sym])
  }
  return [...groups.entries()].map(([reason, symbols]) => ({ reason, symbols }))
}
const failed = computed(() =>
  (audit.value?.checks ?? []).filter((c) => !c.ok).map((c) => ({ ...c, label: auditCheckLabel(c.name), groups: groupSamples(c.samples) })),
)
const openedChecks = ref<string[]>([])

onMounted(() => {
  void load()
  getUniverse().then((u) => (universe.value = u)).catch(() => { /* 中文名只是锦上添花 */ })
})
</script>

<template>
  <div class="page">
    <PageHeader title="入场信号" hint="入场哨兵（规则 sentinel-v1）：每个交易日美东 18:10 评估，模型只有否决权。信号是候选提示与风险预案，不是买入指令">
      <template #actions>
        <el-date-picker :model-value="date" type="date" value-format="YYYY-MM-DD" size="small" style="width: 140px" :clearable="false"
                        @update:model-value="pickDate" />
        <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
      </template>
    </PageHeader>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />

    <section v-if="audit" class="panel">
      <div class="panel__head">
        <h3>{{ audit.date }} 概况</h3>
        <el-tag size="small" :type="audit.ok ? 'success' : 'danger'" effect="plain">审计{{ audit.ok ? '通过' : '不通过' }}</el-tag>
        <span class="panel__grow" />
        <template v-if="settings">
          <el-tag size="small" :type="settings.configured ? 'success' : 'warning'" effect="plain">{{ settings.configured ? '模型密钥已配置' : '未配置模型密钥' }}</el-tag>
          <el-tag size="small" :type="settings.signalVetoEnabled ? 'success' : 'info'" effect="plain">AI 否决{{ settings.signalVetoEnabled ? '已开启' : '未开启' }}</el-tag>
        </template>
      </div>
      <div v-if="summary.tradingDay !== false" class="kv-grid">
        <div class="kv"><label>评估</label><b class="num">{{ summary.evaluations ?? 0 }} / {{ summary.targets ?? '—' }}</b></div>
        <div class="kv"><label>四门全过（新信号）</label><b class="num">{{ summary.signals ?? 0 }}</b></div>
        <div class="kv"><label>其中池与持仓</label><b class="num sig">{{ summary.poolSignals ?? 0 }}</b></div>
        <div class="kv"><label>AI 分析</label><b class="num">{{ summary.aiAnalyses ?? 0 }}</b></div>
        <div class="kv"><label>AI 否决</label><b class="num" :class="{ veto: (summary.aiVetoes as number) > 0 }">{{ summary.aiVetoes ?? 0 }}</b></div>
        <div v-if="settings" class="kv"><label>今天 AI 调用</label><b class="num">{{ callsToday }} / {{ settings.dailyCallLimit }}</b></div>
        <div v-if="settings" class="kv"><label>模型</label><b>{{ settings.model }}</b></div>
      </div>
      <p v-else class="muted">休市，这一天没有评估。</p>

      <div v-if="failed.length" class="issues">
        <div v-for="c in failed" :key="c.name" class="issue" :class="{ 'issue--critical': c.critical }">
          <el-tag size="small" :type="c.critical ? 'danger' : 'warning'">{{ c.critical ? '关键' : '提示' }}</el-tag>
          <b>{{ c.label }}</b><span>{{ c.detail }}</span>
          <el-collapse v-if="c.groups.length" v-model="openedChecks" class="issue__detail">
            <el-collapse-item :name="c.name" :title="c.count > c.samples.length ? `列出 ${c.samples.length} 只（共 ${c.count}，后端只给前 ${c.samples.length} 个样本）` : `涉及 ${c.samples.length} 只`">
              <div v-for="g in c.groups" :key="g.reason" class="issue__group">
                <span class="muted">{{ g.reason }}（{{ g.symbols.length }} 只）</span>
                <span class="issue__syms">{{ g.symbols.join('、') }}</span>
              </div>
            </el-collapse-item>
          </el-collapse>
        </div>
      </div>
    </section>

    <el-tabs v-model="tab">
      <el-tab-pane label="当日" name="today" lazy>
        <TodayTab ref="todayTab" :date="date" :universe="universe" />
      </el-tab-pane>
      <el-tab-pane label="信号" name="signals" lazy>
        <SignalsTab ref="signalsTab" :universe="universe" />
      </el-tab-pane>
      <el-tab-pane label="纸面账本" name="ledger" lazy>
        <LedgerTab ref="ledgerTab" :universe="universe" />
      </el-tab-pane>
      <el-tab-pane label="回放" name="replay" lazy>
        <ReplayTab :universe="universe" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.sig { color: var(--el-color-primary); }
.veto { color: var(--el-color-danger); }
.issues { margin-top: 10px; display: flex; flex-direction: column; gap: 6px; }
.issue { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; font-size: 13px; }
.issue--critical b { color: var(--el-color-danger); }
.issue__detail { flex-basis: 100%; border: 0; }
.issue__detail :deep(.el-collapse-item__header) { height: 28px; font-size: 12px; border-bottom: 0; color: var(--el-text-color-secondary); }
.issue__detail :deep(.el-collapse-item__wrap) { border-bottom: 0; }
.issue__group { display: flex; gap: 8px; font-size: 12px; margin: 2px 0; flex-wrap: wrap; }
.issue__syms { color: var(--el-text-color-regular); }
</style>
