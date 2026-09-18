<script setup lang="ts">
import { computed, onMounted, ref, useTemplateRef } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { signalsApi } from '../api/signals'
import type { AuditReport } from '../api/account'
import { aiApi, type AiSettings, type DailyUsage } from '../api/ai'
import TodayTab from '../components/signals/TodayTab.vue'
import SignalsTab from '../components/signals/SignalsTab.vue'
import LedgerTab from '../components/signals/LedgerTab.vue'
import ReplayTab from '../components/signals/ReplayTab.vue'
import { errMsg, iso, todayEt } from '../components/signals/format'

const audit = ref<AuditReport | null>(null)
const settings = ref<AiSettings | null>(null)
const today = ref<DailyUsage | null>(null)
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

const failed = computed(() => (audit.value?.checks ?? []).filter((c) => !c.ok))
const summary = computed(() => audit.value?.summary ?? {})

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader title="信号"
                hint="入场哨兵 sentinel-v1：每个交易日美东 18:10 评估，模型只有否决权；信号是候选提示与风险预案，不是买入指令">
      <template #actions>
        <el-date-picker :model-value="date" type="date" value-format="YYYY-MM-DD" size="small" style="width: 140px" :clearable="false"
                        @update:model-value="pickDate" />
        <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
      </template>
    </PageHeader>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />

    <el-card v-if="audit" shadow="never" class="status">
      <div class="status__row">
        <el-tag :type="audit.ok ? 'success' : 'danger'" effect="dark">审计{{ audit.ok ? '通过' : '不通过' }}</el-tag>
        <span>{{ audit.date }}</span>
        <template v-if="summary.tradingDay !== false">
          <span>评估 <b>{{ summary.evaluations ?? 0 }}</b> / {{ summary.targets ?? '—' }}</span>
          <span>新信号 <b>{{ summary.signals ?? 0 }}</b>（池与持仓 {{ summary.poolSignals ?? 0 }}）</span>
          <span>AI 分析 {{ summary.aiAnalyses ?? 0 }} · 否决 <b>{{ summary.aiVetoes ?? 0 }}</b></span>
        </template>
        <span v-else class="muted">休市</span>
        <span class="spacer"></span>
        <template v-if="settings">
          <el-tag size="small" :type="settings.configured ? 'success' : 'warning'">{{ settings.configured ? '密钥已配置' : '未配置密钥' }}</el-tag>
          <el-tag size="small" :type="settings.signalVetoEnabled ? 'success' : 'info'">否决{{ settings.signalVetoEnabled ? '已开启' : '未开启' }}</el-tag>
          <span class="muted">今天调用 {{ today?.day === todayEt() ? today.calls : 0 }} / {{ settings.dailyCallLimit }} · {{ settings.model }}</span>
        </template>
      </div>
      <div v-for="c in failed" :key="c.name" class="status__check" :class="{ 'status__check--critical': c.critical }">
        {{ c.critical ? '关键' : '提示' }} · {{ c.name }}：{{ c.detail }}<span v-if="c.samples.length" class="muted">（{{ c.samples.slice(0, 8).join('、') }}）</span>
      </div>
    </el-card>

    <el-tabs v-model="tab">
      <el-tab-pane label="今日评估" name="today" lazy>
        <TodayTab ref="todayTab" :date="date" />
      </el-tab-pane>
      <el-tab-pane label="信号" name="signals" lazy>
        <SignalsTab ref="signalsTab" />
      </el-tab-pane>
      <el-tab-pane label="纸面账本" name="ledger" lazy>
        <LedgerTab ref="ledgerTab" />
      </el-tab-pane>
      <el-tab-pane label="回放" name="replay" lazy>
        <ReplayTab />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.status__row { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; font-size: 13px; }
.status__check { font-size: 13px; margin-top: 6px; color: var(--el-color-warning); }
.status__check--critical { color: var(--el-color-danger); }
.spacer { flex: 1; }
</style>
