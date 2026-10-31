<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { HistogramSeries, LineSeries, LineStyle, createChart, type IChartApi, type ISeriesApi } from 'lightweight-charts'
import type { AccountSnapshot } from '../../api/account'
import { chartLayout, chartTheme } from '../chart'
import { useTheme } from '../../composables/useTheme'
import { money, signed, todayEt, trend } from '../../lib/format'

/**
 * NAV history, switchable between NAV (line) and daily change (bars).
 *
 * The daily change is the NAV difference between two consecutive snapshots and **includes deposits and
 * withdrawals** -- this system has no IBKR daily P&L history, so do not read it as trading P&L.
 * Changes are bars rather than a line: each day is an independent quantity, and a line would draw transitions
 * between days that never existed.
 */
const props = withDefaults(
  defineProps<{
    series: AccountSnapshot[]
    /** The "last N days" in the caption; null = all */
    days: number | null
    liveNav: number | null
    height?: number
    /** The NAV series has not been loaded yet (first load, or a failed load being retried): when empty show "loading" rather than "no snapshots yet" */
    pending?: boolean
  }>(),
  { height: 200, pending: false },
)

type Mode = 'nav' | 'change'
const mode = ref<Mode>('nav')
const box = ref<HTMLDivElement | null>(null)
const { isDark } = useTheme()

// Chart objects stay out of reactive proxies: lightweight-charts compares instances internally, and wrapped in a
// Proxy, updates and removal silently stop working.
const chart = shallowRef<IChartApi | null>(null)
const line = shallowRef<ISeriesApi<'Line'> | null>(null)
const tail = shallowRef<ISeriesApi<'Line'> | null>(null)
const bars = shallowRef<ISeriesApi<'Histogram'> | null>(null)

const rows = computed(() =>
  props.series.filter((s) => s.netLiquidation !== null).sort((a, b) => a.asOfDate.localeCompare(b.asOfDate)),
)
/**
 * Draw a dashed segment from the last snapshot to today's live NAV, only when today (US/Eastern) is really later
 * than the last snapshot: after the 18:00 snapshot is stored both point at the same day, and another segment would
 * draw the same day twice.
 */
const liveTail = computed(() => {
  const last = rows.value.at(-1)
  const today = todayEt()
  if (props.liveNav === null || !last || today <= last.asOfDate) return null
  return { from: last, today, nav: props.liveNav }
})

const changes = computed(() =>
  rows.value.slice(1).map((r, i) => ({ date: r.asOfDate, value: (r.netLiquidation as number) - (rows.value[i].netLiquidation as number) })),
)

/**
 * With few samples fitContent stretches the bars into one solid block that reads as "a period" rather than "a day":
 * cap the width and left-align, so the empty space on the right honestly says "still collecting data".
 */
const MAX_BAR_SPACING = 48

function render() {
  if (!box.value) return
  const t = chartTheme()
  if (!chart.value) {
    chart.value = createChart(box.value, {
      height: props.height,
      // follow the container width (internal ResizeObserver); on resize the `ro` below recomputes the visible range
      autoSize: true,
      ...chartLayout(t),
      grid: { horzLines: { color: t.grid }, vertLines: { visible: false } },
      rightPriceScale: { borderVisible: false, scaleMargins: { top: 0.15, bottom: 0.15 } },
      timeScale: { borderVisible: false },
      handleScroll: false,
      handleScale: false,
      localization: {
        // two decimals on a six-figure NAV are just noise, while a daily change of a few hundred needs them: switch by magnitude
        priceFormatter: (v: number) =>
          Math.abs(v) >= 10_000
            ? v.toLocaleString('en-US', { maximumFractionDigits: 0 })
            : v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }),
      },
    })
    line.value = chart.value.addSeries(LineSeries, { color: t.line, lineWidth: 2, priceLineVisible: false })
    tail.value = chart.value.addSeries(LineSeries, {
      color: t.text, lineWidth: 2, lineStyle: LineStyle.Dashed, priceLineVisible: false, lastValueVisible: true,
    })
    bars.value = chart.value.addSeries(HistogramSeries, { base: 0, priceLineVisible: false, lastValueVisible: false })
  } else {
    chart.value.applyOptions({ ...chartLayout(t), grid: { horzLines: { color: t.grid }, vertLines: { visible: false } } })
    line.value?.applyOptions({ color: t.line })
    tail.value?.applyOptions({ color: t.text })
  }

  if (mode.value === 'nav') {
    line.value?.setData(rows.value.map((r) => ({ time: r.asOfDate, value: r.netLiquidation as number })))
    const lt = liveTail.value
    tail.value?.setData(lt ? [{ time: lt.from.asOfDate, value: lt.from.netLiquidation as number }, { time: lt.today, value: lt.nav }] : [])
    bars.value?.setData([])
  } else {
    line.value?.setData([])
    tail.value?.setData([])
    bars.value?.setData(changes.value.map((c) => ({ time: c.date, value: c.value, color: c.value >= 0 ? t.up : t.down })))
  }

  const ts = chart.value!.timeScale()
  const n = mode.value === 'nav' ? rows.value.length : changes.value.length
  if (mode.value === 'change' && n && box.value.clientWidth / n > MAX_BAR_SPACING) {
    ts.setVisibleLogicalRange({ from: -0.5, to: Math.floor(box.value.clientWidth / MAX_BAR_SPACING) - 0.5 })
  } else {
    ts.fitContent()
  }
}

watch([rows, mode, isDark, liveTail], render, { flush: 'post' })
// when the width changes (first appearance, window resize, sidebar toggle) fitContent must run again: a visible range
// computed for the old width squeezes the whole line to the right
const ro = new ResizeObserver(() => render())
watch(box, (el, old) => {
  if (old) ro.unobserve(old)
  if (el) {
    ro.observe(el)
    render()
  } else {
    // v-if removed the box (fewer than two snapshots): destroy the chart too and rebuild it at the real width when the box returns
    chart.value?.remove()
    chart.value = null
  }
})
onBeforeUnmount(() => {
  ro.disconnect()
  chart.value?.remove()
  chart.value = null
})

const last = computed(() => rows.value.at(-1) ?? null)
const lastChange = computed(() => changes.value.at(-1) ?? null)
</script>

<template>
  <section class="panel">
    <div class="panel__head">
      <h3>净值走势</h3>
      <span v-if="mode === 'nav'" class="panel__src">
        实线 = 盈透净值（每日美东 18:00 快照，{{ days === null ? '全部' : `最近 ${days} 天` }}）<template v-if="liveTail"> · 虚线 = 盈透当前净值</template>
      </span>
      <span v-else class="panel__src">每根柱子 = 相邻两份快照的净值差，含出入金，不是交易盈亏</span>
      <span class="panel__grow" />
      <!-- Pages can put a time-range switch etc. here -->
      <slot name="actions" />
      <el-radio-group v-model="mode" size="small">
        <el-radio-button value="nav">净值</el-radio-button>
        <el-radio-button value="change">日变化</el-radio-button>
      </el-radio-group>
    </div>
    <!-- v-if rather than v-show: a chart built while hidden gets width 0 and the time axis squeezes every point to the far right (observed in production with 5 snapshots) -->
    <div v-if="rows.length > 1" ref="box" class="chart" :style="{ height: height + 'px' }" />
    <p class="panel__foot">
      <template v-if="rows.length > 1">
        {{ rows.length }} 份快照（{{ rows[0].asOfDate }} 起）。
        <template v-if="mode === 'nav'">最新 <b class="num">{{ money(last?.netLiquidation) }}</b></template>
        <template v-else-if="lastChange">最近一日（{{ lastChange.date }}）<b class="num" :class="trend(lastChange.value)">{{ signed(lastChange.value) }}</b></template>
      </template>
      <template v-else-if="rows.length === 1">只有 {{ rows[0].asOfDate }} 一份快照，攒够两天才画得出走势。</template>
      <template v-else-if="pending">加载中…（取数失败时会自动重试）</template>
      <template v-else>还没有账户快照。</template>
    </p>
  </section>
</template>

<style scoped>
.chart { width: 100%; }
.panel__foot b { color: var(--el-text-color-primary); }
.panel__foot b.up { color: var(--tr-up); }
.panel__foot b.down { color: var(--tr-down); }
</style>
