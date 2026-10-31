<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { LineSeries, LineStyle, createChart, type IChartApi, type ISeriesApi } from 'lightweight-charts'
import { chartLayout, chartTheme } from '../chart'
import { useTheme } from '../../composables/useTheme'
import { simApi, type SimEquityRow } from '../../api/selection'
import { errMsg, money, pct } from '../../lib/format'

/** Daily equity of the simulated account (after the close); the dashed line is the initial capital. */
const props = defineProps<{ initial: number }>()

const rows = ref<SimEquityRow[]>([])
const error = ref<string | null>(null)
const { isDark } = useTheme()

onMounted(async () => {
  try {
    rows.value = await simApi.equity('2018-01-01')
  } catch (e) {
    error.value = errMsg(e)
  }
})

const box = ref<HTMLDivElement | null>(null)
const chart = shallowRef<IChartApi | null>(null)
const line = shallowRef<ISeriesApi<'Line'> | null>(null)

function render() {
  if (!box.value) return
  const t = chartTheme()
  if (!chart.value) {
    chart.value = createChart(box.value, {
      height: 220, autoSize: true, ...chartLayout(t),
      handleScroll: { mouseWheel: false }, handleScale: { mouseWheel: false },
    })
    line.value = chart.value.addSeries(LineSeries, { color: t.line, lineWidth: 2, priceLineVisible: false })
    line.value.createPriceLine({ price: props.initial, color: t.border, lineStyle: LineStyle.Dashed, lineWidth: 1, axisLabelVisible: true, title: '初始' })
  } else {
    chart.value.applyOptions(chartLayout(t))
    line.value?.applyOptions({ color: t.line })
  }
  line.value?.setData(rows.value.map((r) => ({ time: r.tradeDate, value: r.equity })))
  chart.value.timeScale().fitContent()
}
watch([rows, isDark], render, { flush: 'post' })
watch(box, (el) => {
  if (el) render()
})
onBeforeUnmount(() => {
  chart.value?.remove()
  chart.value = null
})
</script>

<template>
  <section class="panel">
    <div class="panel__head">
      <h3>逐日权益</h3>
      <span v-if="rows.length" class="panel__src">
        {{ rows[0].tradeDate }} ~ {{ rows[rows.length - 1].tradeDate }} · 最新 {{ money(rows[rows.length - 1].equity) }}（{{ pct(rows[rows.length - 1].equity / initial - 1) }}）
      </span>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-if="rows.length > 1" ref="box" class="chart" />
    <p v-else-if="!error" class="muted">权益只有 {{ rows.length }} 天，等更多交易日再画</p>
  </section>
</template>

<style scoped>
.chart { width: 100%; height: 220px; }
</style>
