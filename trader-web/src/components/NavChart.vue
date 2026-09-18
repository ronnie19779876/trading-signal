<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { LineSeries, createChart, type IChartApi, type ISeriesApi } from 'lightweight-charts'
import { chartLayout, chartTheme } from './chart'
import { useTheme } from '../composables/useTheme'

/** 净值走势：一个交易日一个点（time 为 YYYY-MM-DD）。 */
const props = defineProps<{ points: { time: string; value: number }[]; title?: string }>()

const { isDark } = useTheme()

const el = ref<HTMLDivElement | null>(null)
let chart: IChartApi | null = null
let line: ISeriesApi<'Line'> | null = null
let observer: ResizeObserver | null = null

function render() {
  if (!line) return
  line.setData([...props.points].sort((a, b) => (a.time < b.time ? -1 : 1)))
  chart?.timeScale().fitContent()
}

function applyTheme() {
  if (!chart || !line) return
  const t = chartTheme()
  chart.applyOptions(chartLayout(t))
  line.applyOptions({ color: t.line })
}

onMounted(() => {
  if (!el.value) return
  const t = chartTheme()
  chart = createChart(el.value, { height: 260, ...chartLayout(t) })
  line = chart.addSeries(LineSeries, { color: t.line, lineWidth: 2 })
  observer = new ResizeObserver(() => {
    if (el.value && chart) chart.applyOptions({ width: el.value.clientWidth })
  })
  observer.observe(el.value)
  render()
})

watch(() => props.points, render)
watch(isDark, applyTheme)

onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.remove()
  chart = null
  line = null
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
