<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import KlineChart from '../KlineChart.vue'
import { chartTheme, type ChartPriceLine } from '../chart'
import { getBars, type DailyBar, type InstrumentView } from '../../api/marketdata'
import { fundamentalsApi, type FinancialReport, type FundamentalsOverview } from '../../api/fundamentals'
import { sectorCn } from '../../lib/sector'
import { big, daysAgoEt, errMsg, isoEt, money, negative, num, numMax, signed, timeEt, trend } from '../../lib/format'

/**
 * Detail drawer for one position: header figures (refreshed together with the list), one year of daily bars with
 * a cost line and moving averages, and a fundamentals summary.
 *
 * Bars use forward adjustment: it keeps the latest price unchanged and only adjusts history, which puts it on the
 * same price scale as the IBKR average cost, so the cost line is drawn correctly.
 * Cash instruments (SGOV) are not in the instrument database and have no daily bars or fundamentals.
 */
export interface PositionRow {
  symbol: string
  quantity: number
  averageCost: number | null
  costBasis: number | null
  price: number | null
  change: number | null
  changePct: number | null
  priceAt: string | null
  priceDelayed: boolean
  marketValue: number | null
  dailyPnl: number | null
  unrealizedPnl: number | null
  portfolioPct: number | null
  cashEquivalent: boolean
}

const props = defineProps<{
  row: PositionRow | null
  instrument: InstrumentView | null
  /** LIVE = IBKR live; SNAPSHOT = closing snapshot */
  source: 'LIVE' | 'SNAPSHOT'
}>()
const visible = defineModel<boolean>({ default: false })

/**
 * Only these key metrics are shown. They are matched by name rather than field id: the broker uses different
 * templates per industry and the same metric has different ids (net margin attributable to shareholders is 14005
 * for a general company but 16003 for the insurer BRK.B), and whitespace in names is inconsistent too (a stray
 * space before the "(ROE)" part), hence the whitespace squashing. Items missing from a template are simply not
 * shown (insurers have no gross margin but do have pre-tax margin).
 */
const KEY_METRICS: { name: string; percent: boolean }[] = [
  { name: '毛利率', percent: true },
  { name: '营业利润率', percent: true },
  { name: '税前利润率', percent: true },
  { name: '归母净利率', percent: true },
  { name: '净资产收益率（ROE）', percent: true },
  { name: '投入资本回报率（ROIC）', percent: true },
  { name: '自由现金流与收入比率', percent: true },
  { name: '流动比率', percent: false },
]
const squash = (s: string | null) => (s ?? '').replace(/\s+/g, '')

/** The three moving averages commonly used on US daily charts */
const MA = [20, 50, 200]
const visibleFrom = daysAgoEt(365)

const bars = ref<DailyBar[]>([])
const overview = ref<FundamentalsOverview | null>(null)
const reports = ref<FinancialReport[]>([])
const loadingBars = ref(false)
const loadingFund = ref(false)
const barsError = ref<string | null>(null)
const fundError = ref<string | null>(null)

const symbol = computed(() => props.row?.symbol ?? null)
/** Daily bars and fundamentals exist only for instruments in the database */
const known = computed(() => props.instrument !== null)

async function loadBars(s: string) {
  loadingBars.value = true
  barsError.value = null
  try {
    // fetch 300 extra calendar days to warm up MA200 (about 200 trading days); the chart only shows the last year
    bars.value = await getBars(s, daysAgoEt(365 + 300), isoEt(), 'forward')
  } catch (e) {
    bars.value = []
    barsError.value = errMsg(e)
  } finally {
    loadingBars.value = false
  }
}

async function loadFundamentals(s: string) {
  loadingFund.value = true
  fundError.value = null
  try {
    const [o, r] = await Promise.all([
      fundamentalsApi.overview(s),
      fundamentalsApi.reports(s, 'main_index', 8).catch(() => [] as FinancialReport[]),
    ])
    overview.value = o
    reports.value = r
  } catch (e) {
    overview.value = null
    reports.value = []
    const status = (e as { response?: { status?: number } }).response?.status
    fundError.value = status === 404 ? null : errMsg(e)
  } finally {
    loadingFund.value = false
  }
}

// fetch only when the drawer opens or the symbol changes; header figures follow `row` without refetching
watch([symbol, visible, known], ([s, v, k], old) => {
  if (!v || !s) return
  if (old && old[0] === s && old[1] === v && old[2] === k) return
  bars.value = []
  overview.value = null
  reports.value = []
  if (!k) return
  void loadBars(s)
  void loadFundamentals(s)
}, { immediate: true })

const priceLines = computed<ChartPriceLine[]>(() => {
  const cost = props.row?.averageCost
  return cost === null || cost === undefined ? [] : [{ price: cost, title: '成本', color: chartTheme().line, dashed: true }]
})

/**
 * Quarterly reports only: the annual report and Q4 share the same period end and mixed together show no trend, so
 * only quarters are taken, the latest 4.
 */
const quarters = computed(() => {
  const q = reports.value.filter((r) => !r.periodText.endsWith('FY'))
  return (q.length ? q : reports.value).slice(0, 4)
})
const metricRows = computed(() =>
  KEY_METRICS.map((m) => ({
    ...m,
    cells: quarters.value.map((r) => r.items.find((i) => squash(i.name) === m.name) ?? null),
  })).filter((m) => m.cells.some((c) => c?.value !== null && c?.value !== undefined)),
)

const title = computed(() => {
  const i = props.instrument
  const name = i?.nameCn ?? i?.name ?? overview.value?.name ?? null
  return `${symbol.value ?? ''}${name ? ' · ' + name : ''}`
})
</script>

<template>
  <el-drawer v-model="visible" size="60%" :title="title" destroy-on-close>
    <div v-if="row" class="drawer">
      <div class="meta">
        <el-tag v-if="row.cashEquivalent" size="small" type="info">现金工具</el-tag>
        <template v-if="instrument">
          <el-tag v-if="instrument.role" size="small" effect="plain">{{ instrument.role === 'HOLDING' ? '持仓' : instrument.role === 'POOL' ? '池' : '基准' }}</el-tag>
          <el-tooltip v-if="instrument.sector" :content="instrument.subIndustry ?? instrument.sector" :disabled="!instrument.subIndustry" placement="bottom">
            <span class="muted">{{ sectorCn(instrument.sector) }}</span>
          </el-tooltip>
          <span v-if="instrument.indexes.length" class="muted">{{ instrument.indexes.join(' / ') }}</span>
        </template>
      </div>

      <div class="quote">
        <span class="quote__price num">{{ num(row.price) }}</span>
        <template v-if="source === 'LIVE'">
          <span class="num" :class="trend(row.change)">{{ row.change === null ? '—' : (row.change > 0 ? '+' : '') + num(row.change) }}</span>
          <span class="num" :class="trend(row.changePct)">{{ row.changePct === null ? '—' : (row.changePct > 0 ? '+' : '') + num(row.changePct) + '%' }}</span>
          <span class="muted">盈透行情 · {{ timeEt(row.priceAt) }} ET<template v-if="row.priceDelayed">（延迟行情）</template></span>
        </template>
        <span v-else class="muted">收盘快照价</span>
      </div>

      <div class="kv-grid">
        <div class="kv"><label>数量</label><b class="num">{{ row.quantity.toLocaleString(undefined, { maximumFractionDigits: 4 }) }}</b></div>
        <div class="kv"><label>成本价</label><b class="num">{{ money(row.averageCost) }}</b></div>
        <div class="kv"><label>成本</label><b class="num">{{ money(row.costBasis) }}</b></div>
        <div class="kv"><label>市值</label><b class="num">{{ money(row.marketValue) }}</b></div>
        <div class="kv"><label>当日盈亏</label><b class="num" :class="trend(row.dailyPnl)">{{ signed(row.dailyPnl) }}</b></div>
        <div class="kv"><label>浮动盈亏</label><b class="num" :class="trend(row.unrealizedPnl)">{{ signed(row.unrealizedPnl) }}</b></div>
        <div class="kv"><label>占组合</label><b class="num">{{ row.portfolioPct === null ? '—' : num(row.portfolioPct) + '%' }}</b></div>
      </div>

      <el-empty v-if="!known" :image-size="60"
                :description="row.cashEquivalent ? '现金工具不进标的库，没有日 K 与基本面' : '标的库里没有这只，没有日 K 与基本面'" />

      <template v-else>
        <section class="panel">
          <div class="panel__head">
            <h3>日 K（近一年，前复权）</h3>
            <span class="panel__src">虚线 = 成本价 {{ money(row.averageCost) }}（前复权与盈透成本价同一价格尺度）</span>
          </div>
          <el-alert v-if="barsError" :title="'日 K 取数失败：' + barsError" type="error" :closable="false" />
          <div v-loading="loadingBars">
            <KlineChart v-if="bars.length" :bars="bars" :price-lines="priceLines" :height="340" :ma="MA" :visible-from="visibleFrom" />
            <el-empty v-else-if="!loadingBars && !barsError" description="没有日 K" :image-size="60" />
          </div>
        </section>

        <section v-loading="loadingFund" class="panel">
          <div class="panel__head">
            <h3>基本面</h3>
            <span v-if="overview?.valuation" class="panel__src">估值取自 {{ overview.valuation.asOf.slice(0, 10) }}</span>
            <span class="panel__grow" />
            <router-link :to="{ name: 'fundamentals', query: { symbol: row.symbol } }" class="link">基本面页 →</router-link>
          </div>
          <el-alert v-if="fundError" :title="'基本面取数失败：' + fundError" type="error" :closable="false" />

          <div v-if="overview?.valuation" class="kv-grid">
            <div class="kv"><label>总市值</label><b class="num">{{ big(overview.valuation.marketCap) }}</b></div>
            <div class="kv"><label>市盈率 TTM</label><b class="num" :class="negative(overview.valuation.peTtm)">{{ numMax(overview.valuation.peTtm) }}</b></div>
            <div class="kv"><label>市净率</label><b class="num" :class="negative(overview.valuation.pb)">{{ numMax(overview.valuation.pb) }}</b></div>
            <div class="kv"><label>每股收益</label><b class="num">{{ numMax(overview.valuation.eps) }}</b></div>
            <div class="kv"><label>股息率 TTM</label><b class="num">{{ numMax(overview.valuation.dividendYieldTtm) }}%</b></div>
            <div v-if="overview.valuation.navPerShare !== null" class="kv"><label>净值</label><b class="num">{{ numMax(overview.valuation.navPerShare) }}</b></div>
          </div>

          <div v-if="metricRows.length" class="dtable-wrap metrics">
            <table class="dtable">
              <thead>
                <tr><th>主要指标</th><th v-for="q in quarters" :key="q.periodText" class="r">{{ q.periodText }}</th></tr>
              </thead>
              <tbody>
                <tr v-for="m in metricRows" :key="m.name">
                  <td>{{ m.name }}</td>
                  <td v-for="(c, i) in m.cells" :key="i" class="r num" :class="negative(c?.value)">
                    {{ c?.value === null || c?.value === undefined ? '—' : numMax(c.value) + (m.percent ? '%' : '') }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>


          <el-empty v-if="!loadingFund && !fundError && !overview?.valuation && !metricRows.length" :image-size="60"
                    description="没有基本面数据（基金没有财报）" />
        </section>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer { display: flex; flex-direction: column; gap: 12px; }
.meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.quote { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.quote__price { font-size: 26px; font-weight: 600; color: var(--el-text-color-primary); }
.metrics { margin-top: 10px; }
.link { font-size: 12px; color: var(--el-color-primary); text-decoration: none; }
</style>
