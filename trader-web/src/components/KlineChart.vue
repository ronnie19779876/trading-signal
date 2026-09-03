<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { CandlestickSeries, HistogramSeries, createChart, type IChartApi, type ISeriesApi } from 'lightweight-charts'
import type { DailyBar } from '../api/marketdata'

const props = defineProps<{ bars: DailyBar[]; title?: string }>()

const el = ref<HTMLDivElement | null>(null)
let chart: IChartApi | null = null
let candles: ISeriesApi<'Candlestick'> | null = null
let volume: ISeriesApi<'Histogram'> | null = null
let observer: ResizeObserver | null = null

function render() {
  if (!candles || !volume) return
  const sorted = [...props.bars].sort((a, b) => (a.tradeDate < b.tradeDate ? -1 : 1))
  candles.setData(sorted.map((b) => ({ time: b.tradeDate, open: b.open, high: b.high, low: b.low, close: b.close })))
  volume.setData(sorted.map((b) => ({ time: b.tradeDate, value: b.volume, color: b.close >= b.open ? 'rgba(239,83,80,0.5)' : 'rgba(38,166,154,0.5)' })))
  chart?.timeScale().fitContent()
}

onMounted(() => {
  if (!el.value) return
  chart = createChart(el.value, {
    height: 380,
    layout: { background: { color: 'transparent' }, textColor: '#606266' },
    grid: { vertLines: { color: '#f0f2f5' }, horzLines: { color: '#f0f2f5' } },
    rightPriceScale: { borderColor: '#dcdfe6' },
    timeScale: { borderColor: '#dcdfe6' },
  })
  // 中国习惯：红涨绿跌
  candles = chart.addSeries(CandlestickSeries, { upColor: '#ef5350', downColor: '#26a69a', borderVisible: false, wickUpColor: '#ef5350', wickDownColor: '#26a69a' })
  volume = chart.addSeries(HistogramSeries, { priceFormat: { type: 'volume' }, priceScaleId: 'volume' })
  chart.priceScale('volume').applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } })
  observer = new ResizeObserver(() => {
    if (el.value && chart) chart.applyOptions({ width: el.value.clientWidth })
  })
  observer.observe(el.value)
  render()
})

watch(() => props.bars, render)

onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.remove()
  chart = null
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
