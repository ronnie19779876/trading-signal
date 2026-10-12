<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { HistogramSeries, LineSeries, createChart, type IChartApi, type ISeriesApi } from 'lightweight-charts'
import { chartLayout, chartTheme } from '../chart'
import { useTheme } from '../../composables/useTheme'
import { selectionApi, type Rating, type ScoreView } from '../../api/selection'
import { errMsg } from '../../lib/format'
import { RATING_LABEL, RATINGS } from '../../lib/selection'

/**
 * 单只标的的评分历史：合成分（粗线）与四个因子分（细线），0~100；底部色带是每天的评级。
 * 被排除或因子不齐的日子没有点，线会断开——不补、不插值。
 */
const props = defineProps<{ symbol: string; asOf: string }>()

type Range = '1' | '3' | 'all'
const range = ref<Range>('1')
const RANGES: { value: Range; label: string }[] = [
  { value: '1', label: '1 年' },
  { value: '3', label: '3 年' },
  { value: 'all', label: '全部' },
]
const rows = ref<ScoreView[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const { isDark } = useTheme()

async function load() {
  loading.value = true
  error.value = null
  try {
    const to = new Date(props.asOf)
    const from = range.value === 'all' ? '2018-01-01'
      : new Date(to.getTime() - Number(range.value) * 365 * 86_400_000).toISOString().slice(0, 10)
    // 1 年 / 3 年截止到所选日（只看当时为止）；全部 = 2018 年至最新，选早年日期时才看得到之后的走势
    rows.value = await selectionApi.history(props.symbol, from, range.value === 'all' ? undefined : props.asOf)
  } catch (e) {
    rows.value = []
    error.value = errMsg(e)
  } finally {
    loading.value = false
  }
}
watch([() => props.symbol, () => props.asOf, range], load, { immediate: true })

const LINES = [
  { key: 'composite', label: '合成分', color: '' as string, width: 3 },
  { key: 'valueScore', label: '估值', color: '#e6a23c', width: 1 },
  { key: 'growthScore', label: '成长', color: '#b37feb', width: 1 },
  { key: 'profitScore', label: '盈利', color: '#13c2c2', width: 1 },
  { key: 'momentumScore', label: '动能', color: '#f56c9f', width: 1 },
] as const

/** 评级色带的具体颜色（图表不认 CSS 变量） */
function ratingColors(): Record<Rating, string> {
  const t = chartTheme()
  return { STRONG_BUY: t.up, BUY: t.upFill, HOLD: t.border, SELL: t.downFill, STRONG_SELL: t.down }
}

const box = ref<HTMLDivElement | null>(null)
const chart = shallowRef<IChartApi | null>(null)
const lines = shallowRef<ISeriesApi<'Line'>[]>([])
const band = shallowRef<ISeriesApi<'Histogram'> | null>(null)

function render() {
  if (!box.value) return
  const t = chartTheme()
  if (!chart.value) {
    chart.value = createChart(box.value, {
      height: 300,
      autoSize: true,
      ...chartLayout(t),
      rightPriceScale: { visible: true, borderVisible: false, scaleMargins: { top: 0.05, bottom: 0.12 } },
      timeScale: { borderVisible: false },
      handleScroll: { mouseWheel: false },
      handleScale: { mouseWheel: false },
    })
    lines.value = LINES.map((l) => chart.value!.addSeries(LineSeries, {
      color: l.color || t.line, lineWidth: l.width, priceLineVisible: false, lastValueVisible: l.key === 'composite', title: l.label,
    }))
    band.value = chart.value.addSeries(HistogramSeries, { priceScaleId: 'rating', priceLineVisible: false, lastValueVisible: false })
    chart.value.priceScale('rating').applyOptions({ scaleMargins: { top: 0.92, bottom: 0 }, visible: false })
  } else {
    chart.value.applyOptions(chartLayout(t))
    lines.value[0]?.applyOptions({ color: t.line })
  }
  LINES.forEach((l, i) => {
    lines.value[i]?.setData(rows.value.flatMap((r) => (r[l.key] === null ? [] : [{ time: r.tradeDate, value: r[l.key] as number }])))
  })
  const colors = ratingColors()
  band.value?.setData(rows.value.flatMap((r) => (r.rating ? [{ time: r.tradeDate, value: 1, color: colors[r.rating] }] : [])))
  chart.value.timeScale().fitContent()
}

watch([rows, isDark], render, { flush: 'post' })
watch(box, (el) => {
  if (el) render()
  else {
    chart.value?.remove()
    chart.value = null
  }
})
onBeforeUnmount(() => {
  chart.value?.remove()
  chart.value = null
})

/** 合成分有值的天数：全被排除或因子不齐时一个点都没有，不画空图 */
const plotted = computed(() => rows.value.filter((r) => r.composite !== null).length)
const emptyText = computed(() => {
  if (!rows.value.length) return '这段时间没有评分'
  if (!plotted.value) return `这段时间 ${rows.value.length} 个评分日都没有合成分（被排除或因子不齐）`
  return '截至所选日只有 1 个评分日，选「全部」看之后的走势'
})
const ratedDays = computed(() => rows.value.filter((r) => r.rating).length)
const share = computed(() => RATINGS.map((rt) => ({ rating: rt, n: rows.value.filter((r) => r.rating === rt).length })))
</script>

<template>
  <section class="panel">
    <div class="panel__head">
      <h3>评分历史</h3>
      <span class="panel__src">粗线 = 合成分 · 细线 = 四个因子（0~100）· 底部色带 = 评级</span>
      <span class="panel__grow" />
      <el-radio-group v-model="range" size="small">
        <el-radio-button v-for="r in RANGES" :key="r.value" :value="r.value">{{ r.label }}</el-radio-button>
      </el-radio-group>
    </div>
    <el-alert v-if="error" :title="'评分历史取数失败：' + error" type="error" :closable="false" />
    <div v-loading="loading">
      <div v-if="plotted > 1" ref="box" class="chart" />
      <el-empty v-else-if="!loading && !error" :image-size="60"
                :description="emptyText" />
    </div>
    <p v-if="plotted" class="panel__foot">
      {{ rows.length }} 个评分日，其中评级 {{ ratedDays }} 天：
      <template v-for="s in share" :key="s.rating"><span v-if="s.n">{{ RATING_LABEL[s.rating] }} {{ s.n }} 天　</span></template>
    </p>
  </section>
</template>

<style scoped>
.chart { width: 100%; height: 300px; }
</style>
