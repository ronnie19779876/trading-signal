<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { LineSeries, LineStyle, createChart, type IChartApi, type IPriceLine, type ISeriesApi } from 'lightweight-charts'
import { chartLayout, chartTheme } from '../chart'
import { useTheme } from '../../composables/useTheme'
import { getBars, type DailyBar } from '../../api/marketdata'
import { daysAgoEt, errMsg, isoEt, numMax } from '../../lib/format'

/**
 * P/E history: the daily P/E that comes with the broker's daily bars (static definition, close to the valuation
 * snapshot's "P/E", not TTM), drawn together with the forward-adjusted share price.
 * Pool members and holdings have 20 years of daily bars, others about 3 years.
 *
 * Median and percentile are computed by this system: only positive values count (0 and negatives are excluded
 * because they would skew the percentile), and no percentile is given when the current value is invalid.
 */
const props = defineProps<{ symbol: string }>()

type Range = '1' | '3' | '5' | 'all'
const range = ref<Range>('5')
const RANGES: { value: Range; label: string }[] = [
  { value: '1', label: '1 年' },
  { value: '3', label: '3 年' },
  { value: '5', label: '5 年' },
  { value: 'all', label: '全部' },
]

const bars = ref<DailyBar[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const { isDark } = useTheme()

async function load() {
  loading.value = true
  error.value = null
  try {
    const from = range.value === 'all' ? '2000-01-01' : daysAgoEt(365 * Number(range.value))
    bars.value = (await getBars(props.symbol, from, isoEt(), 'forward')).filter((b) => !b.blank)
  } catch (e) {
    bars.value = []
    error.value = errMsg(e)
  } finally {
    loading.value = false
  }
}
watch([() => props.symbol, range], load, { immediate: true })

/**
 * The broker's daily-bar P/E is 0, not negative or null, for loss periods and for funds (measured: INTC currently 0
 * while the valuation snapshot says -49.99; SPY is 0 throughout), so 0 means "invalid": not drawn and not counted.
 * The current value is the latest day only; if it is 0 no percentile is given, rather than passing off an older
 * value as current.
 */
const valid = (pe: number | null): pe is number => pe !== null && pe !== 0
const peRows = computed(() => bars.value.filter((b) => valid(b.pe)))
const latestPe = computed(() => bars.value.at(-1)?.pe ?? null)
const stats = computed(() => {
  const rows = peRows.value
  const current = valid(latestPe.value) ? latestPe.value : null
  const positive = rows.map((b) => b.pe as number).filter((v) => v > 0).sort((a, b) => a - b)
  if (!positive.length) return { current, median: null, percentile: null, count: 0 }
  const mid = Math.floor(positive.length / 2)
  const median = positive.length % 2 ? positive[mid] : (positive[mid - 1] + positive[mid]) / 2
  const percentile = current !== null && current > 0 ? (positive.filter((v) => v <= current).length / positive.length) * 100 : null
  return { current, median, percentile, count: positive.length }
})
const first = computed(() => bars.value[0]?.tradeDate ?? null)

// ---- chart ----
const box = ref<HTMLDivElement | null>(null)
const chart = shallowRef<IChartApi | null>(null)
const pe = shallowRef<ISeriesApi<'Line'> | null>(null)
const price = shallowRef<ISeriesApi<'Line'> | null>(null)
let medianLine: IPriceLine | null = null

function render() {
  if (!box.value) return
  const t = chartTheme()
  if (!chart.value) {
    chart.value = createChart(box.value, {
      height: 280,
      autoSize: true,
      ...chartLayout(t),
      leftPriceScale: { visible: true, borderVisible: false },
      rightPriceScale: { visible: true, borderVisible: false },
      timeScale: { borderVisible: false },
      // leave the mouse wheel to the page
      handleScroll: { mouseWheel: false },
      handleScale: { mouseWheel: false },
    })
    price.value = chart.value.addSeries(LineSeries, { color: t.text, lineWidth: 1, priceScaleId: 'left', priceLineVisible: false, lastValueVisible: true })
    pe.value = chart.value.addSeries(LineSeries, { color: t.line, lineWidth: 2, priceScaleId: 'right', priceLineVisible: false })
  } else {
    chart.value.applyOptions(chartLayout(t))
    price.value?.applyOptions({ color: t.text })
    pe.value?.applyOptions({ color: t.line })
  }
  price.value?.setData(bars.value.map((b) => ({ time: b.tradeDate, value: b.close })))
  pe.value?.setData(peRows.value.map((b) => ({ time: b.tradeDate, value: b.pe as number })))
  if (medianLine) pe.value?.removePriceLine(medianLine)
  medianLine = null
  if (stats.value.median !== null) {
    medianLine = pe.value!.createPriceLine({
      price: stats.value.median, color: t.line, lineWidth: 1, lineStyle: LineStyle.Dashed, axisLabelVisible: true, title: '中位数',
    })
  }
  chart.value.timeScale().fitContent()
}

watch([bars, isDark], render, { flush: 'post' })
watch(box, (el) => {
  if (el) render()
  else {
    chart.value?.remove()
    chart.value = null
    medianLine = null
  }
})
onBeforeUnmount(() => {
  chart.value?.remove()
  chart.value = null
})

const rangeText = computed(() => (range.value === 'all' ? `${first.value ?? ''} 以来` : `近 ${range.value} 年`))
</script>

<template>
  <section class="panel">
    <div class="panel__head">
      <h3>市盈率走势</h3>
      <span class="panel__src">蓝线 = 市盈率（右轴，券商日 K 的静态口径）· 灰线 = 前复权股价（左轴）</span>
      <span class="panel__grow" />
      <el-radio-group v-model="range" size="small">
        <el-radio-button v-for="r in RANGES" :key="r.value" :value="r.value">{{ r.label }}</el-radio-button>
      </el-radio-group>
    </div>
    <el-alert v-if="error" :title="'日 K 取数失败：' + error" type="error" :closable="false" />
    <div v-loading="loading">
      <div v-if="peRows.length > 1" ref="box" class="chart" />
      <el-empty v-else-if="!loading && !error" :image-size="60" description="没有市盈率数据（基金没有市盈率）" />
    </div>
    <p v-if="peRows.length > 1" class="panel__foot">
      <template v-if="stats.current !== null">当前 <b class="num">{{ numMax(stats.current) }}</b></template>
      <template v-else>最新一天券商日 K 的市盈率为 0（亏损或无数据）</template>
      <template v-if="stats.median !== null"> · {{ rangeText }}中位数 <b class="num">{{ numMax(stats.median) }}</b></template>
      <template v-if="stats.percentile !== null"> · 处于 <b class="num">{{ stats.percentile.toFixed(0) }}%</b> 分位</template>
      <template v-else> · 不计分位</template>
      （本系统按券商日 K 的市盈率计算，只计正值 {{ stats.count }} 天）
    </p>
  </section>
</template>

<style scoped>
.chart { width: 100%; height: 280px; }
.panel__foot b { color: var(--el-text-color-primary); }
</style>
