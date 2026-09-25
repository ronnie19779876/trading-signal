<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import MoneyTiles from '../components/dash/MoneyTiles.vue'
import EquityPanel from '../components/dash/EquityPanel.vue'
import CandidatePanel, { type CandidateRow } from '../components/dash/CandidatePanel.vue'
import HoldingsPanel, { type HoldingRow } from '../components/dash/HoldingsPanel.vue'
import EvaluationDrawer from '../components/signals/EvaluationDrawer.vue'
import { useMarketClock } from '../composables/useMarketClock'
import { useLiveAccount } from '../composables/useLiveAccount'
import { auditCheckLabel } from '../lib/audit'
import { daysAgoEt, errMsg, isoEt } from '../lib/format'
import { accountApi, type AccountSnapshot, type AuditReport, type SnapshotView } from '../api/account'
import { signalsApi, type EvaluationRow } from '../api/signals'
import { getBars, getPool, type DailyBar, type InstrumentView } from '../api/marketdata'
import { getQuotes, type Quote } from '../api/quotes'

/**
 * 仪表盘：一屏看清"我现在怎么样"。布局参考 futu-trader：上面账户资金，中间净值走势，下面左候选、右持仓。
 *
 * 账户与持仓优先用盈透实时（3.0.2，/api/account/live，按需订阅），全是盈透原值；拿不到时退回收盘快照。
 * 候选标的的现价与涨跌用富途实时报价（生产订阅了池与持仓），没有时用最近一根日 K。
 *
 * 刷新分三档：实时账户盘中 1 秒、其余 5 秒（见 useLiveAccount）；日级数据（快照、评估、日 K）5 分钟一轮；
 * 报价在开市时段 5 秒一轮、休市 60 秒。
 * 页面切到后台即暂停。
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
const loaded = ref(false)
const error = ref<string | null>(null)

const drawer = ref(false)
const pickedSymbol = ref<string | null>(null)

const { session } = useMarketClock()

/** 盈透的类别股带空格（BRK B），富途与库里是点（BRK.B）。 */
const norm = (s: string) => s.replace(' ', '.')

async function loadDaily() {
  error.value = null
  try {
    const [latest, snaps, poolRows, sa, aa] = await Promise.all([
      accountApi.latest().catch((e) => {
        if ((e as { response?: { status?: number } }).response?.status === 404) return null // 还没拍过快照
        throw e
      }),
      accountApi.snapshots(daysAgoEt(NAV_DAYS), isoEt()),
      getPool(),
      signalsApi.audit(),
      accountApi.audit(),
    ])
    view.value = latest
    series.value = snaps
    pool.value = poolRows
    signalAudit.value = sa
    accountAudit.value = aa
    await Promise.all([loadEvaluations(sa.date), loadBars()])
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    loaded.value = true
  }
}

/**
 * 审计日有评估就用审计日（哪怕全是"数据过期"，那也是要看到的事实）。
 * 审计日一行都没有（当晚跑批前、或测试库没跑）时，退回最近一个真正判定过的日子，并在标题上写明评估日。
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

/** 近一年日 K（前复权，拆股不断崖）：候选的迷你走势与持仓的当日涨跌都用它。 */
async function loadBars() {
  // 只取池里有的：持仓里的现金工具（SGOV）不进池、库里没有日 K，请求它只会 404
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
    // 报价是锦上添花：取不到就按收盘口径显示
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

/** 有盈透实时持仓（LIVE，或断线但留着最后的数据）就用它，否则用收盘快照。都是券商原值，不做折算。 */
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

/** 两份审计里没通过的项：只在有问题时出现，关键项排前。 */
const problems = computed(() =>
  [
    ...(signalAudit.value?.checks ?? []).filter((c) => !c.ok).map((c) => ({ ...c, from: '信号' })),
    ...(accountAudit.value?.checks ?? []).filter((c) => !c.ok).map((c) => ({ ...c, from: '账户' })),
  ].sort((a, b) => Number(b.critical) - Number(a.critical)),
)

// ---- 刷新节奏：页面切到后台即暂停 ----
let dailyTimer = 0
let quoteTimer = 0
/**
 * 报价轮询是「自递归 setTimeout」，必须有个「还在运行吗」的守卫。
 * 没有守卫时：请求在途中调用 stop()，stop 只清掉当前那个 timer，
 * 而回调在请求返回后照样调 scheduleQuotes() 把链条重新排起来——
 * 此后这条链再也没人清得掉，页面切到后台仍在打请求，离开页面也一样
 * （2026-09-25 全项目审查发现）。
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
  running = true
  void loadDaily()
  startLive()
  void loadQuotes().then(scheduleQuotes)
  dailyTimer = window.setInterval(loadDaily, 5 * 60_000)
}
function stop() {
  running = false
  window.clearInterval(dailyTimer)
  window.clearTimeout(quoteTimer)
  stopLive()
  dailyTimer = quoteTimer = 0
}
function onVisibility() {
  if (document.hidden) stop()
  else if (!dailyTimer) start()
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
    <el-alert v-if="error" :title="'取数失败：' + error" type="error" show-icon :closable="false" />

    <div v-if="problems.length" class="banner">
      <b>需要处理</b>
      <span v-for="c in problems" :key="c.from + c.name" class="banner__item" :class="{ 'banner__item--critical': c.critical }">
        {{ c.from }} · {{ auditCheckLabel(c.name) }}：{{ c.detail }}
      </span>
    </div>

    <MoneyTiles :view="view" :live="live" :session="session" :loaded="loaded" />
    <EquityPanel :series="series" :days="NAV_DAYS" :live-nav="liveNav" />
    <div class="two-col">
      <CandidatePanel :rows="candidates" :eval-date="evalDate" :loaded="loaded" @open="openCandidate" />
      <HoldingsPanel :rows="holdings" :source="liveHoldings ? 'LIVE' : 'SNAPSHOT'" :as-of="view?.snapshot.asOfDate ?? null"
                     :eval-date="evalDate" :loaded="loaded"
                     @open="openCandidate" />
    </div>

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
