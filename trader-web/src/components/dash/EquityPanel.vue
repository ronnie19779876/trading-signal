<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { HistogramSeries, LineSeries, LineStyle, createChart, type IChartApi, type ISeriesApi } from 'lightweight-charts'
import type { AccountSnapshot } from '../../api/account'
import { chartLayout, chartTheme } from '../chart'
import { useTheme } from '../../composables/useTheme'
import { money, signed, todayEt, trend } from '../../lib/format'

/**
 * 净值走势：净值（折线）/ 日变化（柱）两种口径切换。
 *
 * 日变化是相邻两份快照的净值差，**含出入金**——本系统没有盈透的当日盈亏，别把它读成交易盈亏。
 * 盈亏用柱子而不是折线：它是每天独立的一个量，折线会画出两天之间并不存在的过渡。
 */
const props = defineProps<{ series: AccountSnapshot[]; days: number; liveNav: number | null }>()

type Mode = 'nav' | 'change'
const mode = ref<Mode>('nav')
const box = ref<HTMLDivElement | null>(null)
const { isDark } = useTheme()

// 图表对象不进响应式代理：lightweight-charts 内部按实例比对，被 Proxy 包住后更新与销毁会静默失效。
const chart = shallowRef<IChartApi | null>(null)
const line = shallowRef<ISeriesApi<'Line'> | null>(null)
const tail = shallowRef<ISeriesApi<'Line'> | null>(null)
const bars = shallowRef<ISeriesApi<'Histogram'> | null>(null)

const rows = computed(() =>
  props.series.filter((s) => s.netLiquidation !== null).sort((a, b) => a.asOfDate.localeCompare(b.asOfDate)),
)
/**
 * 从最后一份快照往今天画一段虚线到实时净值。条件是今天确实晚于最后一份快照：
 * 当天 18:00 快照落库后两者指向同一天，再画一段就是把同一天画两次。
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

/** 样本少时柱子会被 fitContent 撑成整块色块，读起来像"一段"而不是"一天"：限宽、左对齐，右侧留白如实表达"还在攒数据"。 */
const MAX_BAR_SPACING = 48

function render() {
  if (!box.value) return
  const t = chartTheme()
  if (!chart.value) {
    chart.value = createChart(box.value, {
      height: 200,
      // 跟着容器宽度走（内部 ResizeObserver）；尺寸变了再由下面的 ro 重算可见范围
      autoSize: true,
      ...chartLayout(t),
      grid: { horzLines: { color: t.grid }, vertLines: { visible: false } },
      rightPriceScale: { borderVisible: false, scaleMargins: { top: 0.15, bottom: 0.15 } },
      timeScale: { borderVisible: false },
      handleScroll: false,
      handleScale: false,
      localization: {
        // 净值六位数带两位小数只是噪音，日变化几百块不带小数又看不出差别：按量级切
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
// 宽度变了（首次出现、窗口缩放、侧栏开合）要重新 fitContent：否则按旧宽度算的可见范围会把整条线挤在右边
const ro = new ResizeObserver(() => render())
watch(box, (el, old) => {
  if (old) ro.unobserve(old)
  if (el) {
    ro.observe(el)
    render()
  } else {
    // v-if 拿掉了盒子（快照不足两份）：图跟着销毁，下次盒子出现时按真实宽度重建
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
        实线 = 盈透净值（每日美东 18:00 快照，最近 {{ days }} 天）<template v-if="liveTail"> · 虚线 = 盈透当前净值</template>
      </span>
      <span v-else class="panel__src">每根柱子 = 相邻两份快照的净值差，含出入金，不是交易盈亏</span>
      <span class="panel__grow" />
      <el-radio-group v-model="mode" size="small">
        <el-radio-button value="nav">净值</el-radio-button>
        <el-radio-button value="change">日变化</el-radio-button>
      </el-radio-group>
    </div>
    <!-- v-if 而不是 v-show：隐藏时建图拿到的宽度是 0，时间轴会把所有点挤在最右边（生产 5 份快照时实测） -->
    <div v-if="rows.length > 1" ref="box" class="chart" />
    <p class="panel__foot">
      <template v-if="rows.length > 1">
        {{ rows.length }} 份快照（{{ rows[0].asOfDate }} 起）。
        <template v-if="mode === 'nav'">最新 <b class="num">{{ money(last?.netLiquidation) }}</b></template>
        <template v-else-if="lastChange">最近一日（{{ lastChange.date }}）<b class="num" :class="trend(lastChange.value)">{{ signed(lastChange.value) }}</b></template>
      </template>
      <template v-else-if="rows.length === 1">只有 {{ rows[0].asOfDate }} 一份快照，攒够两天才画得出走势。</template>
      <template v-else>还没有账户快照。</template>
    </p>
  </section>
</template>

<style scoped>
.chart { width: 100%; height: 200px; }
.panel__foot b { color: var(--el-text-color-primary); }
.panel__foot b.up { color: var(--tr-up); }
.panel__foot b.down { color: var(--tr-down); }
</style>
