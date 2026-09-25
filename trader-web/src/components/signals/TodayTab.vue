<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { signalsApi, type EvaluationRow, type Gate, type SignalStatus, type SignalView } from '../../api/signals'
import type { InstrumentView } from '../../api/marketdata'
import GateDots from './GateDots.vue'
import EvaluationDrawer from './EvaluationDrawer.vue'
import SignalDetailDrawer from './SignalDetailDrawer.vue'
import { usePager } from '../../composables/usePager'
import { sectorCn } from '../../lib/sector'
import {
  GATES, GATE_LABEL, OUTCOME_FILTERS, OUTCOME_LABEL, ROLE_LABEL, SIGNAL_STATUS_LABEL, SIGNAL_STATUS_TYPE, STANCE_LABEL, STANCE_TYPE, STATUS_LABEL,
  TRACK_LABEL, errMsg, num, pct,
} from './format'

/**
 * 评估日的全部情况：当天新信号（卡片，点开看详情）、四门漏斗与分布、评估表（回答"为什么没信号、卡在哪道门"）。
 * 默认只看池与持仓；切到"全部"才列出池外的信号与评估。
 */
const props = defineProps<{ date: string | null; universe: InstrumentView[] }>()

const rows = ref<EvaluationRow[]>([])
const signals = ref<SignalView[]>([])
const scope = ref<'pool' | 'all'>('pool')
const outcome = ref<string>('')
const gate = ref<string>('')
const loading = ref(false)
const error = ref<string | null>(null)

const evalDrawer = ref(false)
const picked = ref<EvaluationRow | null>(null)
const signalDrawer = ref(false)
const pickedSignal = ref<number | null>(null)

async function load() {
  if (!props.date) return
  loading.value = true
  error.value = null
  try {
    // 都取全部，池与持仓在前端筛：切换范围不用再请求，还能告诉用户池外有多少
    const [e, s] = await Promise.all([
      signalsApi.evaluations({ date: props.date, scope: 'all' }),
      signalsApi.list({ from: props.date, to: props.date, scope: 'all' }),
    ])
    rows.value = e
    signals.value = s
  } catch (e) {
    error.value = errMsg(e)
    rows.value = []
    signals.value = []
  } finally {
    loading.value = false
  }
}
watch(() => props.date, load, { immediate: true })

const names = computed(() => new Map(props.universe.map((u) => [u.symbol, u])))
const nameOf = (s: string) => names.value.get(s)?.nameCn ?? names.value.get(s)?.name ?? null
const inScope = (role: string) => scope.value === 'all' || role !== 'UNIVERSE'

// ---- 当天新信号：持仓、池、池外依次 ----
const ROLE_ORDER: Record<string, number> = { HOLDING: 0, POOL: 1, UNIVERSE: 2 }
const cards = computed(() =>
  signals.value
    .filter((v) => inScope(v.signal.role))
    .sort((a, b) => ROLE_ORDER[a.signal.role] - ROLE_ORDER[b.signal.role] || a.signal.symbol.localeCompare(b.signal.symbol)),
)
const hiddenSignals = computed(() => signals.value.length - cards.value.length)
function openSignal(id: number) {
  pickedSignal.value = id
  signalDrawer.value = true
}

// ---- 漏斗与分布 ----
const scoped = computed(() => rows.value.filter((r) => inScope(r.role)))
const evaluated = computed(() => scoped.value.filter((r) => r.gates))
const funnel = computed(() =>
  GATES.map((g, k) => {
    const count = evaluated.value.filter((r) => r.gates!.slice(0, k + 1).split('').every((c) => c === 'P')).length
    return {
      gate: g,
      label: GATES.slice(0, k + 1).map((x) => GATE_LABEL[x]).join(' + '),
      count,
      percent: evaluated.value.length ? Math.round((count / evaluated.value.length) * 100) : 0,
    }
  }),
)
function count(values: string[]): [string, number][] {
  const m = new Map<string, number>()
  values.forEach((v) => m.set(v, (m.get(v) ?? 0) + 1))
  return [...m.entries()]
}
const statusCounts = computed(() => count(scoped.value.map((r) => STATUS_LABEL[r.status])))
const outcomeCounts = computed(() => count(scoped.value.filter((r) => r.outcome).map((r) => OUTCOME_LABEL[r.outcome!])))

// ---- 评估表：过门多的在前，分页 ----
const filtered = computed(() =>
  scoped.value
    .filter((r) => !outcome.value || r.outcome === outcome.value || r.status === outcome.value)
    .filter((r) => !gate.value || r.firstBlockingGate === (gate.value as Gate))
    .sort((a, b) => b.gatesPassed - a.gatesPassed || a.symbol.localeCompare(b.symbol)),
)
const { page, pageSize, paged, total } = usePager(filtered, 20, [outcome, gate])

function openEval(row: EvaluationRow) {
  picked.value = row
  evalDrawer.value = true
}

defineExpose({ load })
</script>

<template>
  <div v-loading="loading" class="tab">
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <div class="toolbar">
      <el-radio-group v-model="scope" size="small">
        <el-radio-button value="pool">池与持仓</el-radio-button>
        <el-radio-button value="all">全部</el-radio-button>
      </el-radio-group>
      <span class="muted">{{ date }} · 评估 {{ scoped.length }} 只</span>
    </div>

    <!-- 当天新信号 -->
    <section class="panel">
      <div class="panel__head">
        <h3>当天新信号</h3>
        <span class="panel__src">价位为判定日口径；点卡片看四门判据、K 线、AI 意见与纸面账本</span>
        <span class="panel__grow" />
        <span v-if="hiddenSignals" class="panel__src">另有池外 {{ hiddenSignals }} 条，切到"全部"查看</span>
      </div>
      <div v-if="cards.length" class="cards">
        <div v-for="v in cards" :key="v.signal.id" class="card" :class="{ 'card--veto': v.signal.status === 'VETOED' }" @click="openSignal(v.signal.id)">
          <div class="card__head">
            <b class="card__sym">{{ v.signal.symbol }}</b>
            <span v-if="nameOf(v.signal.symbol)" class="card__name">{{ nameOf(v.signal.symbol) }}</span>
            <span class="panel__grow" />
            <el-tag size="small" effect="plain" :type="v.signal.role === 'HOLDING' ? 'success' : 'info'">{{ ROLE_LABEL[v.signal.role] }}</el-tag>
            <el-tag size="small" :type="SIGNAL_STATUS_TYPE[v.signal.status as SignalStatus]">{{ SIGNAL_STATUS_LABEL[v.signal.status as SignalStatus] }}</el-tag>
          </div>
          <div class="card__grid">
            <span>收盘</span><b class="num">{{ num(v.signal.close) }}</b>
            <span>止损</span><b class="num down">{{ num(v.signal.stop) }} <small>（{{ pct(v.signal.stopDistance, 1) }}）</small></b>
            <span>+1R</span><b class="num up">{{ num(v.signal.plusOneR) }}</b>
            <span>目标 · 盈亏比</span><b class="num">{{ num(v.signal.target) }} · {{ num(v.signal.rewardRisk) }}</b>
          </div>
          <div class="card__foot">
            <el-tag v-if="v.signal.aiStance" size="small" effect="plain" :type="STANCE_TYPE[v.signal.aiStance as keyof typeof STANCE_TYPE] ?? 'info'">
              AI {{ STANCE_LABEL[v.signal.aiStance as keyof typeof STANCE_LABEL] ?? v.signal.aiStance }}
            </el-tag>
            <span v-else class="muted">没有 AI 意见</span>
            <span class="panel__grow" />
            <span class="muted">账本 {{ v.base ? TRACK_LABEL[v.base.status] : '—' }} · 有效至 {{ v.signal.expiresOn }}</span>
          </div>
        </div>
      </div>
      <el-empty v-else :image-size="50" :description="hiddenSignals ? '池与持仓这一天没有新信号' : '这一天没有新信号'" />
    </section>

    <!-- 漏斗与分布 -->
    <div class="two">
      <section class="panel">
        <div class="panel__head"><h3>四门漏斗</h3><span class="panel__src">依次通过前几道门的只数（四门各自判定，不短路）</span></div>
        <div v-for="f in funnel" :key="f.gate" class="funnel">
          <span class="funnel__label">{{ f.label }}</span>
          <el-progress :percentage="f.percent" :format="() => String(f.count)" :stroke-width="12" />
        </div>
        <div v-if="!evaluated.length" class="muted">这一天还没有评估结果</div>
      </section>
      <section class="panel">
        <div class="panel__head"><h3>分布</h3></div>
        <div class="dist">
          <span class="dist__k">状态</span>
          <el-tag v-for="[k, n] in statusCounts" :key="k" size="small" type="info">{{ k }} {{ n }}</el-tag>
          <span v-if="!statusCounts.length" class="muted">—</span>
        </div>
        <div class="dist">
          <span class="dist__k">结果</span>
          <el-tag v-for="[k, n] in outcomeCounts" :key="k" size="small" :type="k === '信号' ? 'primary' : k === 'AI 否决' ? 'danger' : 'info'">{{ k }} {{ n }}</el-tag>
          <span v-if="!outcomeCounts.length" class="muted">—</span>
        </div>
      </section>
    </div>

    <!-- 评估表 -->
    <section class="panel">
      <div class="panel__head">
        <h3>评估明细</h3>
        <span class="panel__src">过门多的在前；点一行看当天的四门判定过程</span>
        <span class="panel__grow" />
        <el-select v-model="outcome" size="small" clearable placeholder="结果 / 状态" style="width: 140px">
          <el-option v-for="k in OUTCOME_FILTERS" :key="k" :label="OUTCOME_LABEL[k]" :value="k" />
          <el-option v-for="(label, k) in STATUS_LABEL" :key="k" :label="label" :value="k" />
        </el-select>
        <el-select v-model="gate" size="small" clearable placeholder="卡在哪道门" style="width: 130px">
          <el-option v-for="g in GATES" :key="g" :label="GATE_LABEL[g]" :value="g" />
        </el-select>
      </div>
      <div class="dtable-wrap">
        <table class="dtable">
          <thead>
            <tr>
              <th>代码</th><th>行业</th><th>角色</th><th>结果</th><th>四门</th><th>卡在</th>
              <th class="r">收盘</th><th class="r">RVOL</th><th class="r">止损距离</th><th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in paged" :key="r.symbol" class="clickable" @click="openEval(r)">
              <td><b>{{ r.symbol }}</b><span v-if="nameOf(r.symbol)" class="name">{{ nameOf(r.symbol) }}</span></td>
              <td class="muted-cell">{{ sectorCn(names.get(r.symbol)?.sector) ?? '—' }}</td>
              <td>{{ ROLE_LABEL[r.role] }}</td>
              <td>
                <el-tag v-if="r.outcome" size="small" :type="r.outcome === 'SIGNAL' ? 'primary' : r.outcome === 'BLOCKED_BY_AI' ? 'danger' : 'info'">
                  {{ OUTCOME_LABEL[r.outcome] }}
                </el-tag>
                <el-tag v-else size="small" type="warning">{{ STATUS_LABEL[r.status] }}</el-tag>
              </td>
              <td><GateDots :gates="r.gates" /></td>
              <td>{{ r.firstBlockingGate ? GATE_LABEL[r.firstBlockingGate] : '—' }}</td>
              <td class="r num">{{ num(r.close) }}</td>
              <td class="r num">{{ num(r.rvol) }}</td>
              <td class="r num">{{ pct(r.stopDistance) }}</td>
              <td class="muted-cell wrap">{{ r.statusDetail ?? '' }}</td>
            </tr>
            <tr v-if="!filtered.length">
              <td colspan="10" class="empty">{{ rows.length ? '没有符合条件的评估' : '这一天没有评估结果：看信号评估作业跑了没有' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="total > pageSize" class="pager">
        <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="total" :page-sizes="[20, 50, 100]"
                       layout="total, sizes, prev, pager, next, jumper" size="small" background />
      </div>
    </section>

    <EvaluationDrawer v-model="evalDrawer" :symbol="picked?.symbol ?? null" :date="picked?.tradeDate ?? null" />
    <SignalDetailDrawer v-model="signalDrawer" :signal-id="pickedSignal" @changed="load" />
  </div>
</template>

<style scoped>
.tab { display: flex; flex-direction: column; gap: 12px; }
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 10px; }
.card {
  border: 1px solid var(--el-border-color-lighter); border-left: 3px solid var(--el-color-primary); border-radius: 8px;
  padding: 10px 12px; cursor: pointer; background: var(--el-bg-color); transition: background 0.15s;
}
.card:hover { background: var(--el-fill-color-light); }
.card--veto { border-left-color: var(--el-color-danger); }
.card__head { display: flex; align-items: center; gap: 6px; margin-bottom: 8px; }
.card__sym { font-size: 16px; }
.card__name { font-size: 12px; color: var(--el-text-color-secondary); }
.card__grid { display: grid; grid-template-columns: auto 1fr; gap: 3px 12px; font-size: 13px; }
.card__grid span { color: var(--el-text-color-secondary); font-size: 12px; }
.card__grid b { text-align: right; font-weight: 600; }
.card__grid small { font-weight: 400; color: var(--el-text-color-secondary); }
.card__foot { display: flex; align-items: center; gap: 6px; margin-top: 8px; font-size: 12px; }
.two { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px; }
@media (max-width: 1100px) { .two { grid-template-columns: minmax(0, 1fr); } }
.funnel { display: grid; grid-template-columns: 170px 1fr; align-items: center; gap: 8px; margin: 6px 0; font-size: 13px; }
.dist { margin: 8px 0; display: flex; gap: 6px; flex-wrap: wrap; align-items: center; font-size: 13px; }
.dist__k { color: var(--el-text-color-secondary); width: 36px; }
.dtable { font-size: 13px; }
.name { margin-left: 8px; font-size: 12px; color: var(--el-text-color-secondary); }
.muted-cell { color: var(--el-text-color-secondary); }
.wrap { white-space: normal; }
.pager { display: flex; justify-content: flex-end; margin-top: 10px; }
</style>
