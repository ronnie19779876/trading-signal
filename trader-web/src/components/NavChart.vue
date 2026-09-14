<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { LineSeries, createChart, type IChartApi, type ISeriesApi } from 'lightweight-charts'

/** 净值走势：一个交易日一个点（time 为 YYYY-MM-DD）。 */
const props = defineProps<{ points: { time: string; value: number }[]; title?: string }>()

const el = ref<HTMLDivElement | null>(null)
let chart: IChartApi | null = null
let line: ISeriesApi<'Line'> | null = null
let observer: ResizeObserver | null = null

function render() {
  if (!line) return
  line.setData([...props.points].sort((a, b) => (a.time < b.time ? -1 : 1)))
  chart?.timeScale().fitContent()
}

onMounted(() => {
  if (!el.value) return
  chart = createChart(el.value, {
    height: 260,
    layout: { background: { color: 'transparent' }, textColor: '#606266' },
    grid: { vertLines: { color: '#f0f2f5' }, horzLines: { color: '#f0f2f5' } },
    rightPriceScale: { borderColor: '#dcdfe6' },
    timeScale: { borderColor: '#dcdfe6' },
  })
  line = chart.addSeries(LineSeries, { color: '#409eff', lineWidth: 2 })
  observer = new ResizeObserver(() => {
    if (el.value && chart) chart.applyOptions({ width: el.value.clientWidth })
  })
  observer.observe(el.value)
  render()
})

watch(() => props.points, render)

onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.remove()
  chart = null
})
</script>

<template>
  <div>
    <div v-if="title" class="nav__title">{{ title }}</div>
    <div ref="el" class="nav"></div>
  </div>
</template>

<style scoped>
.nav { width: 100%; }
.nav__title { font-size: 13px; color: var(--el-text-color-regular); margin-bottom: 6px; }
</style>
