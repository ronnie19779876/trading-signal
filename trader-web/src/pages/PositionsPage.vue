<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import { useLiveAccount } from '../composables/useLiveAccount'
import { useRecoveringLoad, type Stages } from '../composables/useRecoveringLoad'
import RecoveryAlert from '../components/RecoveryAlert.vue'
import PositionDrawer, { type PositionRow } from '../components/positions/PositionDrawer.vue'
import { money, num, signed, timeEt, trend } from '../lib/format'
import { sectorCn } from '../lib/sector'
import { accountApi, type HoldingReview, type HoldingReviewItem, type SnapshotView } from '../api/account'
import { getPool, type InstrumentView } from '../api/marketdata'

/**
 * Positions page: a full-width position list; clicking a row slides in a detail drawer from the right (header
 * figures, daily bars with a cost line, fundamentals summary).
 *
 * Same data rules as the dashboard: IBKR live first (/api/account/live), all raw IBKR values with no conversion;
 * otherwise the latest closing snapshot.
 * The selected instrument lives in ?symbol=, so refreshing or sharing returns to the same one (not /positions/BRK.B:
 * frontend routes are single-segment and contain no dot).
 *
 * Refresh: the live account every 1 s during the session and 5 s otherwise (see useLiveAccount); pool and snapshot
 * one round every 5 minutes; everything pauses while the page is in the background.
 */
const route = useRoute()
const router = useRouter()

const { live, start: startLive, stop: stopLive } = useLiveAccount()
const view = ref<SnapshotView | null>(null)
const pool = ref<InstrumentView[]>([])

/** IBKR share classes contain a space (BRK B); Futu and the database use a dot (BRK.B). Only the first space is replaced. */
const norm = (s: string) => s.replace(' ', '.')

const isLive = computed(() => {
  const l = live.value
  return !!l && (l.status === 'LIVE' || l.status === 'DISCONNECTED') && l.positionsUpdatedAt !== null
})
const source = computed<'LIVE' | 'SNAPSHOT'>(() => (isLive.value ? 'LIVE' : 'SNAPSHOT'))

const instruments = computed(() => new Map(pool.value.map((p) => [p.symbol, p])))

/** Market value descending, cash instruments last (they are a large share but not positions to review one by one). */
const rows = computed<PositionRow[]>(() => {
  const list: PositionRow[] = isLive.value
    ? live.value!.positions.map((p) => ({
        symbol: p.symbol,
        quantity: p.quantity,
        averageCost: p.averageCost,
        costBasis: p.costBasis,
        price: p.last,
        change: p.change,
        changePct: p.changePct,
        priceAt: p.lastAt,
        priceDelayed: p.lastDelayed,
        marketValue: p.marketValue,
        dailyPnl: p.dailyPnl,
        unrealizedPnl: p.unrealizedPnl,
        portfolioPct: p.portfolioPct,
        cashEquivalent: p.cashEquivalent,
      }))
    : (view.value?.positions ?? []).map((p) => ({
        symbol: norm(p.symbol),
        quantity: p.quantity,
        averageCost: p.averageCost,
        costBasis: null,
        price: p.price,
        change: null,
        changePct: null,
        priceAt: null,
        priceDelayed: false,
        marketValue: p.marketValue,
        dailyPnl: null,
        unrealizedPnl: p.unrealizedPnl,
        portfolioPct: null,
        cashEquivalent: p.cashEquivalent,
      }))
  return list.sort((a, b) => Number(a.cashEquivalent) - Number(b.cashEquivalent) || (b.marketValue ?? 0) - (a.marketValue ?? 0))
})

const stockCount = computed(() => rows.value.filter((r) => !r.cashEquivalent).length)
const cashCount = computed(() => rows.value.length - stockCount.value)

// ---- drawer: the selection lives in the URL ----
const picked = computed(() => (typeof route.query.symbol === 'string' ? route.query.symbol : null))
const pickedRow = computed(() => rows.value.find((r) => r.symbol === picked.value) ?? null)
const drawer = computed({
  get: () => pickedRow.value !== null,
  set: (open: boolean) => { if (!open) void router.replace({ query: {} }) },
})
function open(symbol: string) {
  void router.replace({ query: { symbol } })
}

function nameOf(symbol: string): string | null {
  const i = instruments.value.get(symbol)
  return i?.nameCn ?? i?.name ?? null
}
function signedNum(v: number | null): string {
  return v === null ? '—' : (v > 0 ? '+' : '') + num(v)
}

// ---- loading ----
// The blocks are independent and retried automatically on failure; only a success overwrites, a failure keeps the
// previous data (3.4.3, see useRecoveringLoad)
/** Holdings risk panel (3.5.0, §29): only the trend and next-earnings columns are used here; the details are in the dashboard's holdings risk panel */
const review = ref<HoldingReview | null>(null)
const reviewBySymbol = computed(() => new Map((review.value?.items ?? []).map((i) => [i.symbol, i])))
const TIMING: Record<string, string> = { PRE_MARKET: '盘前', DURING_MARKET: '盘中', AFTER_MARKET: '盘后', UNKNOWN: '时段未知' }
function reviewOf(symbol: string): HoldingReviewItem | undefined {
  return reviewBySymbol.value.get(norm(symbol))
}
function stateType(s: string | undefined): 'success' | 'warning' | 'danger' | 'info' {
  return s === 'UP_STRONG' ? 'success' : s === 'BROKEN' ? 'danger' : s === 'UNAVAILABLE' || !s ? 'info' : 'warning'
}

const daily = useRecoveringLoad((): Stages => [{
  池: async () => { pool.value = await getPool() },
  持仓风险: async () => { review.value = await accountApi.holdingsReview() },
  最新快照: async () => {
    view.value = await accountApi.latest().catch((e) => {
      if ((e as { response?: { status?: number } }).response?.status === 404) return null // no snapshot taken yet
      throw e
    })
  },
}], 5 * 60_000)
const loaded = daily.loaded


let running = false
function start() {
  if (running) return
  running = true
  daily.start()
  startLive()
}
function stop() {
  running = false
  daily.stop()
  stopLive()
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
</script>

<template>
  <div class="page">
    <PageHeader title="持仓" hint="盈透持仓，全部为盈透原值（与盈透 App 一致）；点一行看日 K、成本线与基本面" />
    <RecoveryAlert :error="daily.errorText.value" :retry-in="daily.retryIn.value" :busy="daily.busy.value" @retry="daily.retryNow" />

    <section class="panel">
      <div class="summary">
        <div class="summary__item">
          <label>持仓</label>
          <b class="num">{{ stockCount }} 只</b>
          <span v-if="cashCount" class="muted">+ 现金工具 {{ cashCount }}</span>
        </div>
        <template v-if="source === 'LIVE'">
          <div class="summary__item">
            <label>股票市值</label>
            <b class="num">{{ money(live?.money?.stockMarketValue) }}</b>
          </div>
          <div class="summary__item">
            <label>当日盈亏</label>
            <b v-if="live?.pnl" class="num" :class="trend(live.pnl.daily)">{{ signed(live.pnl.daily) }}</b>
            <span v-else class="muted">等待盈透推送…</span>
          </div>
          <div class="summary__item">
            <label>浮动盈亏</label>
            <b v-if="live?.pnl" class="num" :class="trend(live.pnl.unrealized)">{{ signed(live.pnl.unrealized) }}</b>
            <span v-else class="muted">等待盈透推送…</span>
          </div>
          <span class="panel__grow" />
          <span class="panel__src">盈透实时 · 持仓 {{ timeEt(live?.positionsUpdatedAt) }} ET · 盈亏 {{ timeEt(live?.pnl?.updatedAt) }} ET</span>
        </template>
        <template v-else>
          <div class="summary__item">
            <label>股票市值</label>
            <b class="num">{{ money(view?.snapshot.stockMarketValue) }}</b>
          </div>
          <span class="panel__grow" />
          <span class="panel__src">
            盈透实时暂不可用<template v-if="live?.status === 'WARMING'">（正在订阅…）</template>，显示
            {{ view ? view.snapshot.asOfDate + ' 收盘快照' : '—（还没有快照）' }}
          </span>
        </template>
      </div>
    </section>

    <section class="panel">
      <div class="dtable-wrap">
        <table class="dtable">
          <thead>
            <tr>
              <th>代码</th><th>行业</th><th class="r">数量</th><th class="r">成本价</th>
              <th v-if="source === 'LIVE'" class="r">成本</th>
              <th class="r">{{ source === 'LIVE' ? '最新价' : '收盘价' }}</th>
              <template v-if="source === 'LIVE'"><th class="r">涨跌</th><th class="r">涨跌 %</th></template>
              <th class="r">市值</th>
              <th v-if="source === 'LIVE'" class="r">当日盈亏</th>
              <th class="r">浮动盈亏</th>
              <th v-if="source === 'LIVE'" class="r">占组合</th>
              <th>趋势</th><th>下次财报</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.symbol" class="clickable" :class="{ picked: r.symbol === picked }" @click="open(r.symbol)">
              <td>
                <b>{{ r.symbol }}</b>
                <span v-if="nameOf(r.symbol)" class="name">{{ nameOf(r.symbol) }}</span>
                <el-tag v-if="r.cashEquivalent" size="small" type="info" class="tag">现金工具</el-tag>
              </td>
              <td class="muted-cell" :title="instruments.get(r.symbol)?.subIndustry ?? ''">{{ sectorCn(instruments.get(r.symbol)?.sector) ?? '—' }}</td>
              <td class="r num">{{ r.quantity.toLocaleString(undefined, { maximumFractionDigits: 4 }) }}</td>
              <td class="r num">{{ money(r.averageCost) }}</td>
              <td v-if="source === 'LIVE'" class="r num">{{ money(r.costBasis) }}</td>
              <td class="r num">
                {{ num(r.price) }}<span v-if="r.priceDelayed" class="stamp">延迟</span>
              </td>
              <template v-if="source === 'LIVE'">
                <td class="r num" :class="trend(r.change)">{{ signedNum(r.change) }}</td>
                <td class="r num" :class="trend(r.changePct)">{{ r.changePct === null ? '—' : signedNum(r.changePct) + '%' }}</td>
              </template>
              <td class="r num">{{ money(r.marketValue) }}</td>
              <td v-if="source === 'LIVE'" class="r num" :class="trend(r.dailyPnl)">{{ signed(r.dailyPnl) }}</td>
              <td class="r num" :class="trend(r.unrealizedPnl)">{{ signed(r.unrealizedPnl) }}</td>
              <td v-if="source === 'LIVE'" class="r num">{{ r.portfolioPct === null ? '—' : num(r.portfolioPct) + '%' }}</td>
              <td>
                <el-tooltip v-if="reviewOf(r.symbol)?.trend" placement="top"
                            :content="`距 200 日均线 ${num(reviewOf(r.symbol)!.trend!.pctVsSmaLong)}%（${reviewOf(r.symbol)!.trend!.date}）`">
                  <el-tag size="small" :type="stateType(reviewOf(r.symbol)?.trend?.state)">{{ reviewOf(r.symbol)!.trend!.stateLabel }}</el-tag>
                </el-tooltip>
                <span v-else class="muted-cell">—</span>
              </td>
              <td :class="{ 'next-warn': reviewOf(r.symbol)?.earnings?.next?.inWindow }">
                <template v-if="reviewOf(r.symbol)?.earnings?.next">
                  {{ reviewOf(r.symbol)!.earnings!.next!.date }} {{ TIMING[reviewOf(r.symbol)!.earnings!.next!.timing] }}{{ reviewOf(r.symbol)!.earnings!.next!.estimated ? '（估计）' : '' }}
                  <span class="muted-cell">· 第 {{ reviewOf(r.symbol)!.earnings!.next!.tradingDaysAhead }} 个交易日</span>
                </template>
                <span v-else class="muted-cell">—</span>
              </td>
            </tr>
            <tr v-if="!rows.length">
              <td :colspan="source === 'LIVE' ? 14 : 9" class="empty">{{ loaded ? '无持仓' : '加载中…' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-if="source === 'LIVE'" class="panel__foot">
        最新价与前收来自盈透行情，市值、当日盈亏、浮动盈亏来自盈透逐只盈亏；涨跌 = 最新价 − 前收，成本 = 成本价 × 数量，占组合 = 市值 ÷ 净值，与盈透 App 同口径。
        按市值从大到小，现金工具排最后。
      </p>
      <p v-else class="panel__foot">收盘价与市值是快照当晚按收盘价的估值。</p>
    </section>

    <PositionDrawer v-model="drawer" :row="pickedRow" :instrument="pickedRow ? instruments.get(pickedRow.symbol) ?? null : null" :source="source" />
  </div>
</template>

<style scoped>
.summary { display: flex; align-items: baseline; gap: 28px; flex-wrap: wrap; }
.summary__item { display: flex; align-items: baseline; gap: 8px; }
.summary__item label { font-size: 12px; color: var(--el-text-color-secondary); }
.summary__item b { font-size: 16px; font-weight: 600; }
.dtable { font-size: 13px; }
.name { margin-left: 8px; font-size: 12px; color: var(--el-text-color-secondary); }
.tag { margin-left: 6px; }
.muted-cell { color: var(--el-text-color-secondary); }
.stamp {
  display: inline-block; margin-left: 3px; font-size: 10px; line-height: 14px; padding: 0 3px; border-radius: 3px;
  color: var(--el-color-warning); background: var(--el-color-warning-light-9);
}
tr.picked td { background: var(--el-color-primary-light-9); }
.next-warn { color: var(--el-color-warning-dark-2); font-weight: 600; }
</style>
