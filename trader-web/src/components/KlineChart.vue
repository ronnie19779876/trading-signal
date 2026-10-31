<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  CandlestickSeries,
  HistogramSeries,
  LineSeries,
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
import { MA_COLORS, chartLayout, chartTheme, movingAverage, type ChartMarker, type ChartPriceLine, type ChartZone, type KlineBar } from './chart'
import { useTheme } from '../composables/useTheme'

/**
 * Candlestick chart with volume, optional simple moving averages, price lines, zones and markers
 * (lightweight-charts). The chart objects are plain variables, not refs: lightweight-charts compares instances
 * internally and breaks when they are wrapped in a reactive Proxy. Created on mount, removed on unmount.
 */
const props = withDefaults(
  defineProps<{
    bars: KlineBar[]
    title?: string
    height?: number
    markers?: ChartMarker[]
    priceLines?: ChartPriceLine[]
    zones?: ChartZone[]
    /** Moving-average periods (simple MA of the close), empty = none; the chart has toggles, and which ones are hidden is remembered in this browser */
    ma?: number[]
    /** Only show this date and later; earlier bars are used only to warm up the moving averages (MA200 needs 200 bars) */
    visibleFrom?: string
    /** localStorage key for MA visibility; each context keeps its own (signal charts hide them by default, the position drawer shows them) */
    maKey?: string
    /** Hide the moving averages when nothing is stored locally */
    maHiddenByDefault?: boolean
  }>(),
  {
    height: 380, markers: () => [], priceLines: () => [], zones: () => [], ma: () => [], visibleFrom: '',
    maKey: 'kline.ma.hidden', maHiddenByDefault: false,
  },
)

const { isDark } = useTheme()

const el = ref<HTMLDivElement | null>(null)
let chart: IChartApi | null = null
let candles: ISeriesApi<'Candlestick'> | null = null
let volume: ISeriesApi<'Histogram'> | null = null
let markerApi: ISeriesMarkersPluginApi<Time> | null = null
let lines: IPriceLine[] = []
let maSeries = new Map<number, ISeriesApi<'Line'>>()

// ---- MA toggles: hidden periods are remembered locally (if storage is unavailable, fall back to the default) ----
function readHidden(): number[] {
  const fallback = props.maHiddenByDefault ? [...props.ma] : []
  try {
    const raw = localStorage.getItem(props.maKey)
    if (raw === null) return fallback
    const v = JSON.parse(raw)
    return Array.isArray(v) ? v.filter((x) => typeof x === 'number') : fallback
  } catch {
    return fallback
  }
}
const hidden = ref<number[]>(readHidden())
function toggleMa(period: number) {
  hidden.value = hidden.value.includes(period) ? hidden.value.filter((p) => p !== period) : [...hidden.value, period]
  try {
    localStorage.setItem(props.maKey, JSON.stringify(hidden.value))
  } catch {
    // if it cannot be stored it only applies to this page view
  }
  maSeries.get(period)?.applyOptions({ visible: !hidden.value.includes(period) })
}
const maColor = (i: number) => MA_COLORS[i % MA_COLORS.length]
/** The legend shows each moving average's value on the last day */
const maLast = ref<Map<number, number | null>>(new Map())
let observer: ResizeObserver | null = null

function render() {
  if (!candles || !volume) return
  const t = chartTheme()
  const all = [...props.bars].sort((a, b) => (a.tradeDate < b.tradeDate ? -1 : 1))
  const sorted = props.visibleFrom ? all.filter((b) => b.tradeDate >= props.visibleFrom) : all
  candles.setData(sorted.map((b) => ({ time: b.tradeDate, open: b.open, high: b.high, low: b.low, close: b.close })))
  volume.setData(sorted.map((b) => ({ time: b.tradeDate, value: b.volume, color: b.close >= b.open ? t.upFill : t.downFill })))
  renderMa(all)
  renderOverlays()
  chart?.timeScale().fitContent()
}

/** Moving averages are computed over all bars (including the warm-up part) but only the visible part is drawn. */
function renderMa(all: KlineBar[]) {
  if (!chart) return
  for (const [period, series] of maSeries) {
    if (!props.ma.includes(period)) {
      chart.removeSeries(series)
      maSeries.delete(period)
    }
  }
  const last = new Map<number, number | null>()
  props.ma.forEach((period, i) => {
    let series = maSeries.get(period)
    if (!series) {
      series = chart!.addSeries(LineSeries, {
        color: maColor(i), lineWidth: 1, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false,
        visible: !hidden.value.includes(period),
      })
      maSeries.set(period, series)
    }
    const points = movingAverage(all, period).filter((p) => !props.visibleFrom || p.time >= props.visibleFrom)
    series.setData(points)
    last.set(period, points.at(-1)?.value ?? null)
  })
  maLast.value = last
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
  // only the visible part counts (bars before visibleFrom only warm up the moving averages and are not drawn)
  const dates = new Set(props.bars.filter((b) => !props.visibleFrom || b.tradeDate >= props.visibleFrom).map((b) => b.tradeDate))
  // marker dates must exist among the bars or the chart library throws; they must also be in ascending time order
  const markers: SeriesMarker<Time>[] = props.markers
    .filter((m) => dates.has(m.time))
    .sort((a, b) => (a.time < b.time ? -1 : 1))
    .map((m) => ({ time: m.time, position: m.position, shape: m.shape, color: m.color, text: m.text ?? '' }))
  if (markerApi) markerApi.setMarkers(markers)
  else markerApi = createSeriesMarkers(candles, markers)
}

/** Theme switch: chart colors are not CSS and cannot be restyled, so read them again and reapply (volume bar colors are part of the data, so redraw). */
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
  chart = createChart(el.value, {
    height: props.height,
    ...chartLayout(t),
    // the mouse wheel is left to page scrolling: scrolling over the chart no longer zooms or pans it (dragging and
    // trackpad pinch still work)
    handleScroll: { mouseWheel: false },
    handleScale: { mouseWheel: false },
  })
  // up/down colors come from tokens.css (US convention: green up, red down)
  candles = chart.addSeries(CandlestickSeries, { upColor: t.up, downColor: t.down, borderVisible: false, wickUpColor: t.up, wickDownColor: t.down })
  volume = chart.addSeries(HistogramSeries, { priceFormat: { type: 'volume' }, priceScaleId: 'volume' })
  chart.priceScale('volume').applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } })
  observer = new ResizeObserver(() => {
    if (el.value && chart) chart.applyOptions({ width: el.value.clientWidth })
  })
  observer.observe(el.value)
  render()
})

watch(() => [props.bars, props.ma, props.visibleFrom], render)
watch(() => [props.markers, props.priceLines, props.zones], renderOverlays, { deep: true })
watch(isDark, applyTheme)

onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.remove()
  chart = null
  candles = null
  markerApi = null
  lines = []
  maSeries = new Map()
})
</script>

<template>
  <div>
    <div v-if="title" class="kline__title">{{ title }}</div>
    <div v-if="ma.length" class="kline__ma">
      <button v-for="(p, i) in ma" :key="p" type="button" class="ma-chip" :class="{ off: hidden.includes(p) }"
              :title="hidden.includes(p) ? '点击显示' : '点击隐藏'" @click="toggleMa(p)">
        <i :style="{ background: maColor(i) }" />MA{{ p }}
        <span class="num">{{ maLast.get(p) == null ? '—' : maLast.get(p)!.toFixed(2) }}</span>
      </button>
    </div>
    <div ref="el" class="kline"></div>
  </div>
</template>

<style scoped>
.kline { width: 100%; }
.kline__title { font-size: 13px; color: var(--el-text-color-regular); margin-bottom: 6px; }
.kline__ma { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 6px; }
.ma-chip {
  display: inline-flex; align-items: center; gap: 5px; padding: 1px 8px; border-radius: 10px; cursor: pointer;
  font-size: 11px; line-height: 18px; color: var(--el-text-color-regular);
  background: var(--el-fill-color-lighter); border: 1px solid var(--el-border-color-lighter);
}
.ma-chip i { display: inline-block; width: 10px; height: 2px; border-radius: 1px; }
.ma-chip.off { opacity: 0.45; text-decoration: line-through; }
</style>
