<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import MoneyTiles from '../components/dash/MoneyTiles.vue'
import EquityPanel from '../components/dash/EquityPanel.vue'
import CandidatePanel, { type CandidateRow } from '../components/dash/CandidatePanel.vue'
import HoldingsPanel, { type HoldingRow } from '../components/dash/HoldingsPanel.vue'
import RiskPanel from '../components/dash/RiskPanel.vue'
import EvaluationDrawer from '../components/signals/EvaluationDrawer.vue'
import { useMarketClock } from '../composables/useMarketClock'
import { useLiveAccount } from '../composables/useLiveAccount'
import { useRecoveringLoad, type Stages } from '../composables/useRecoveringLoad'
import RecoveryAlert from '../components/RecoveryAlert.vue'
import { auditCheckLabel } from '../lib/audit'
import { daysAgoEt, isoEt } from '../lib/format'
import { accountApi, type AccountSnapshot, type AuditReport, type HoldingReview, type SnapshotView } from '../api/account'
import { signalsApi, type EvaluationRow } from '../api/signals'
import { getBars, getPool, type DailyBar, type InstrumentView } from '../api/marketdata'
import { getQuotes, type Quote } from '../api/quotes'

/**
 * Dashboard: "how am I doing right now" on one screen. Layout modelled on futu-trader: account funds on top, NAV
 * history in the middle, candidates on the left and holdings on the right below.
 *
 * Account and holdings prefer IBKR live (3.0.2, /api/account/live, subscribed on demand), all raw IBKR values;
 * without it they fall back to the closing snapshot. Candidate prices and changes use Futu live quotes (production
 * subscribes the pool and holdings), falling back to the latest daily bar.
 *
 * Three refresh tiers: the live account every 1 s during the session and 5 s otherwise (see useLiveAccount);
 * daily-granularity data (snapshots, evaluations, daily bars) one round every 5 minutes; quotes every 5 s while the
 * market is open and 60 s when closed.
 * Everything pauses while the page is in the background. Daily blocks are independent and retried automatically on
 * failure (3.4.3, see useRecoveringLoad).
 * Since 3.5.0 the bottom has the holdings risk panel (trend, earnings warnings, review triggers, add-position
 * check, §29).
 */
const NAV_DAYS = 180

const view = ref<SnapshotView | null>(null)
const { live, start: startLive, stop: stopLive } = useLiveAccount()
const series = ref<AccountSnapshot[]>([])
const pool = ref<InstrumentView[]>([])
const evaluations = ref<EvaluationRow[]>([])
const evalDate = ref<string | null>(null)
const barsBySymbol = ref<Map<string, DailyBar[]>>(new Map())
const quotes = ref<Map<string, Quote>>(new Map())
const signalAudit = ref<AuditReport | null>(null)
const accountAudit = ref<AuditReport | null>(null)
const holdingReview = ref<HoldingReview | null>(null)

const drawer = ref(false)
const pickedSymbol = ref<string | null>(null)

const { session } = useMarketClock()

/** IBKR share classes contain a space (BRK B); Futu and the database use a dot (BRK.B). Only the first space is replaced. */
const norm = (s: string) => s.replace(' ', '.')

/**
 * Daily-granularity data in two stages: first, concurrently, the snapshot, NAV series, pool and the audits; then
 * the evaluations for the audit date and the daily bars for the pool.
 * Each block only overwrites its own data on success; a failed block keeps its previous data (previously a single
 * timeout discarded the whole batch and NAV and pool both showed as empty, 2026-10-13).
 */
const daily = useRecoveringLoad((): Stages => [
  {
    最新快照: async () => {
      view.value = await accountApi.latest().catch((e) => {
        if ((e as { response?: { status?: number } }).response?.status === 404) return null // no snapshot taken yet
        throw e
      })
    },
    净值序列: async () => { series.value = await accountApi.snapshots(daysAgoEt(NAV_DAYS), isoEt()) },
    池: async () => { pool.value = await getPool() },
    信号审计: async () => { signalAudit.value = await signalsApi.audit() },
    账户审计: async () => { accountAudit.value = await accountApi.audit() },
    持仓风险: async () => { holdingReview.value = await accountApi.holdingsReview() },
  },
  {
    评估: async () => { if (signalAudit.value) await loadEvaluations(signalAudit.value.date) },
    日K: loadBars,
  },
], 5 * 60_000)
const loaded = daily.loaded
/** A block that has never loaded, or failed this round: the panel shows "loading" instead of "no data" */
const pending = (name: string) => !daily.loaded.value || daily.failedNames.value.has(name)

/**
 * If the audit date has evaluations, use the audit date (even if they are all "stale data" -- that is a fact worth
 * seeing). If it has no rows at all (before that evening's batch, or on a test database that never ran), fall back
 * to the latest day that was really evaluated and state the evaluation date in the title.
 */
async function loadEvaluations(auditDate: string) {
  let rows = await signalsApi.evaluations({ date: auditDate, scope: 'pool' })
  let date: string | null = auditDate
  if (!rows.length) {
    const probe = pool.value.find((p) => p.role === 'POOL' || p.role === 'HOLDING')
    const history = probe ? await signalsApi.history(probe.symbol) : []
    date = history.find((h) => h.status === 'EVALUATED')?.tradeDate ?? null
    rows = date ? await signalsApi.evaluations({ date, scope: 'pool' }) : []
  }
  evaluations.value = rows
  evalDate.value = rows.length ? date : null
}

/** About one year of daily bars (forward-adjusted, so splits do not create cliffs): used by the candidates' sparklines and the holdings' daily change. */
async function loadBars() {
  // only instruments in the pool: cash instruments among the holdings (SGOV) are not in the pool and have no daily
  // bars, so requesting them would only 404
  const inPool = new Set(pool.value.map((p) => p.symbol))
  const symbols = [...new Set([
    ...pool.value.filter((p) => p.role === 'POOL' || p.role === 'HOLDING').map((p) => p.symbol),
    ...(view.value?.positions ?? []).map((p) => norm(p.symbol)).filter((s) => inPool.has(s)),
  ])]
  const from = daysAgoEt(365)
  const to = isoEt()
  const pairs = await Promise.all(
    symbols.map((s) => getBars(s, from, to, 'forward').then((b) => [s, b] as const).catch(() => [s, [] as DailyBar[]] as const)),
  )
  barsBySymbol.value = new Map(pairs)
}


async function loadQuotes() {
  try {
    const list = await getQuotes()
    quotes.value = new Map(list.map((q) => [q.instrument.symbol, q]))
  } catch {
    // quotes are a nice-to-have: if they cannot be loaded, show closing figures
  }
}

const candidates = computed<CandidateRow[]>(() => {
  const evalBySymbol = new Map(evaluations.value.map((e) => [e.symbol, e]))
  return pool.value
    .filter((p) => p.role === 'POOL')
    .map((p) => {
      const bars = barsBySymbol.value.get(p.symbol) ?? []
      const q = quotes.value.get(p.symbol)
      const bar = bars.at(-1) ?? null
      return {
        symbol: p.symbol,
        name: p.nameCn ?? p.name,
        spark: bars.map((b) => b.close),
        price: q?.price ?? bar?.close ?? null,
        changeRate: q ? q.changeRate : bar?.changeRate ?? null,
        live: !!q?.price,
        session: q?.session ?? null,
        priceDate: bar?.tradeDate ?? null,
        evaluation: evalBySymbol.get(p.symbol) ?? null,
      }
    })
})

/** Use IBKR live positions when available (LIVE, or disconnected but still holding the last data), otherwise the closing snapshot. Both are raw broker values with no conversion. */
const liveHoldings = computed(() => {
  const l = live.value
  return !!l && (l.status === 'LIVE' || l.status === 'DISCONNECTED') && l.positionsUpdatedAt !== null
})
const liveNav = computed(() => live.value?.money?.netLiquidation ?? null)

const holdings = computed<HoldingRow[]>(() => {
  const evalBySymbol = new Map(evaluations.value.map((e) => [e.symbol, e]))
  if (liveHoldings.value) {
    return live.value!.positions.map((p) => ({
      symbol: p.symbol,
      quantity: p.quantity,
      averageCost: p.averageCost,
      costBasis: p.costBasis,
      price: p.last,
      change: p.change,
      changePct: p.changePct,
      portfolioPct: p.portfolioPct,
      priceAt: p.lastAt,
      priceDelayed: p.lastDelayed,
      marketValue: p.marketValue,
      dailyPnl: p.dailyPnl,
      unrealizedPnl: p.unrealizedPnl,
      cashEquivalent: p.cashEquivalent,
      outcome: evalBySymbol.get(p.symbol)?.outcome ?? null,
    }))
  }
  return (view.value?.positions ?? []).map((p) => {
    const symbol = norm(p.symbol)
    return {
      symbol,
      quantity: p.quantity,
      averageCost: p.averageCost,
      costBasis: null,
      price: p.price,
      change: null,
      changePct: null,
      portfolioPct: null,
      priceAt: null,
      priceDelayed: false,
      marketValue: p.marketValue,
      dailyPnl: null,
      unrealizedPnl: p.unrealizedPnl,
      cashEquivalent: p.cashEquivalent,
      outcome: evalBySymbol.get(symbol)?.outcome ?? null,
    }
  })
})

/** Failed checks from the two audits: shown only when there is a problem, critical items first. */
const problems = computed(() =>
  [
    ...(signalAudit.value?.checks ?? []).filter((c) => !c.ok).map((c) => ({ ...c, from: '信号' })),
    ...(accountAudit.value?.checks ?? []).filter((c) => !c.ok).map((c) => ({ ...c, from: '账户' })),
  ].sort((a, b) => Number(b.critical) - Number(a.critical)),
)

// ---- refresh cadence: pause while the page is in the background ----
let quoteTimer = 0
/**
 * Quote polling is a self-rescheduling setTimeout, so it must have an "are we still running" guard.
 * Without it: stop() called while a request is in flight only clears the current timer, but after the request
 * returns the callback calls scheduleQuotes() anyway and re-arms the chain -- from then on nobody can clear it, and
 * it keeps firing requests in the background and even after leaving the page (found in the 2026-09-25
 * full-project review).
 * scheduleQuotes() also clears the single timer handle first, so a quick stop()/start() cannot leave two chains.
 */
let running = false
function scheduleQuotes() {
  window.clearTimeout(quoteTimer)
  if (!running) return
  const active = session.value !== 'CLOSED' && quotes.value.size > 0
  quoteTimer = window.setTimeout(async () => {
    if (!running) return
    await loadQuotes()
    scheduleQuotes()
  }, active ? 5_000 : 60_000)
}
function start() {
  if (running) return
  running = true
  daily.start()
  startLive()
  void loadQuotes().then(scheduleQuotes)
}
function stop() {
  running = false
  window.clearTimeout(quoteTimer)
  daily.stop()
  stopLive()
  quoteTimer = 0
}
function onVisibility() {
  if (document.hidden) stop()
  else start()
}
onMounted(() => {
  start()
  document.addEventListener('visibilitychange', onVisibility)
})
onBeforeUnmount(() => {
  stop()
  document.removeEventListener('visibilitychange', onVisibility)
})

function openCandidate(symbol: string) {
  pickedSymbol.value = symbol
  drawer.value = true
}
</script>

<template>
  <div class="dash">
    <RecoveryAlert :error="daily.errorText.value" :retry-in="daily.retryIn.value" :busy="daily.busy.value" @retry="daily.retryNow" />

    <div v-if="problems.length" class="banner">
      <b>需要处理</b>
      <span v-for="c in problems" :key="c.from + c.name" class="banner__item" :class="{ 'banner__item--critical': c.critical }">
        {{ c.from }} · {{ auditCheckLabel(c.name) }}：{{ c.detail }}
      </span>
    </div>

    <MoneyTiles :view="view" :live="live" :session="session" :loaded="!pending('最新快照')" />
    <EquityPanel :series="series" :days="NAV_DAYS" :live-nav="liveNav" :pending="pending('净值序列')" />
    <div class="two-col">
      <CandidatePanel :rows="candidates" :eval-date="evalDate" :loaded="!pending('池')" @open="openCandidate" />
      <HoldingsPanel :rows="holdings" :source="liveHoldings ? 'LIVE' : 'SNAPSHOT'" :as-of="view?.snapshot.asOfDate ?? null"
                     :eval-date="evalDate" :loaded="loaded"
                     @open="openCandidate" />
    </div>
    <RiskPanel :review="holdingReview" :pending="pending('持仓风险')" />

    <EvaluationDrawer v-model="drawer" :symbol="pickedSymbol" :date="evalDate" />
  </div>
</template>

<style scoped>
.dash { display: flex; flex-direction: column; gap: 12px; }
.two-col { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px; align-items: start; }
@media (max-width: 1180px) { .two-col { grid-template-columns: minmax(0, 1fr); } }
.banner {
  display: flex; flex-direction: column; gap: 2px; padding: 8px 12px; border-radius: 8px; font-size: 12px;
  background: var(--el-color-warning-light-9); border: 1px solid var(--el-color-warning-light-5); color: var(--el-color-warning-dark-2);
}
.banner__item--critical { color: var(--el-color-danger); }
</style>
