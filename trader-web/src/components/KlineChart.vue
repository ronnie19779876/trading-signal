<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  CandlestickSeries,
  HistogramSeries,
  LineStyle,
  createChart,
  createSeriesMarkers,
  type IChartApi,
  type IPriceLine,
  type ISeriesApi,
  type ISeriesMarkersPluginApi,
  type SeriesMarker,
  type Time,
} from 'lightweight-charts'
import { chartLayout, chartTheme, type ChartMarker, type ChartPriceLine, type ChartZone, type KlineBar } from './chart'
import { useTheme } from '../composables/useTheme'

const props = withDefaults(
  defineProps<{
    bars: KlineBar[]
    title?: string
    height?: number
    markers?: ChartMarker[]
    priceLines?: ChartPriceLine[]
    zones?: ChartZone[]
  }>(),
  { height: 380, markers: () => [], priceLines: () => [], zones: () => [] },
)

const { isDark } = useTheme()

const el = ref<HTMLDivElement | null>(null)
let chart: IChartApi | null = null
let candles: ISeriesApi<'Candlestick'> | null = null
let volume: ISeriesApi<'Histogram'> | null = null
let markerApi: ISeriesMarkersPluginApi<Time> | null = null
let lines: IPriceLine[] = []
let observer: ResizeObserver | null = null

function render() {
  if (!candles || !volume) return
  const t = chartTheme()
  const sorted = [...props.bars].sort((a, b) => (a.tradeDate < b.tradeDate ? -1 : 1))
  candles.setData(sorted.map((b) => ({ time: b.tradeDate, open: b.open, high: b.high, low: b.low, close: b.close })))
  volume.setData(sorted.map((b) => ({ time: b.tradeDate, value: b.volume, color: b.close >= b.open ? t.upFill : t.downFill })))
  renderOverlays()
  chart?.timeScale().fitContent()
}

function renderOverlays() {
  if (!candles) return
  lines.forEach((l) => candles?.removePriceLine(l))
  lines = []
  for (const p of props.priceLines) {
    lines.push(candles.createPriceLine({
      price: p.price, color: p.color, title: p.title, lineWidth: 1,
      lineStyle: p.dashed ? LineStyle.Dashed : LineStyle.Solid, axisLabelVisible: true,
    }))
  }
  for (const z of props.zones) {
    const color = z.color ?? '#909399'
    lines.push(candles.createPriceLine({ price: z.top, color, title: `${z.title} 顶`, lineWidth: 1, lineStyle: LineStyle.Dotted, axisLabelVisible: false }))
    lines.push(candles.createPriceLine({ price: z.bottom, color, title: `${z.title} 底`, lineWidth: 1, lineStyle: LineStyle.Dotted, axisLabelVisible: false }))
  }
  const dates = new Set(props.bars.map((b) => b.tradeDate))
  // 标记的日期必须在 K 线里，否则图表库报错；并且要按时间升序
  const markers: SeriesMarker<Time>[] = props.markers
    .filter((m) => dates.has(m.time))
    .sort((a, b) => (a.time < b.time ? -1 : 1))
    .map((m) => ({ time: m.time, position: m.position, shape: m.shape, color: m.color, text: m.text ?? '' }))
  if (markerApi) markerApi.setMarkers(markers)
  else markerApi = createSeriesMarkers(candles, markers)
}

/** 主题切换：图表配色不是 CSS，改不到，只能重新取值套一遍（成交量柱的颜色在数据里，要重画）。 */
function applyTheme() {
  if (!chart || !candles) return
  const t = chartTheme()
  chart.applyOptions(chartLayout(t))
  candles.applyOptions({ upColor: t.up, downColor: t.down, wickUpColor: t.up, wickDownColor: t.down })
  render()
}

onMounted(() => {
  if (!el.value) return
  const t = chartTheme()
  chart = createChart(el.value, { height: props.height, ...chartLayout(t) })
  // 涨跌色来自 tokens.css（美股口径绿涨红跌）
  candles = chart.addSeries(CandlestickSeries, { upColor: t.up, downColor: t.down, borderVisible: false, wickUpColor: t.up, wickDownColor: t.down })
  volume = chart.addSeries(HistogramSeries, { priceFormat: { type: 'volume' }, priceScaleId: 'volume' })
  chart.priceScale('volume').applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } })
  observer = new ResizeObserver(() => {
    if (el.value && chart) chart.applyOptions({ width: el.value.clientWidth })
  })
  observer.observe(el.value)
  render()
})

watch(() => props.bars, render)
watch(() => [props.markers, props.priceLines, props.zones], renderOverlays, { deep: true })
watch(isDark, applyTheme)

onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.remove()
  chart = null
  candles = null
  markerApi = null
  lines = []
})
</script>

<template>
  <div>
    <div v-if="title" class="kline__title">{{ title }}</div>
    <div ref="el" class="kline"></div>
  </div>
</template>

<style scoped>
.kline { width: 100%; }
.kline__title { font-size: 13px; color: var(--el-text-color-regular); margin-bottom: 6px; }
</style>
