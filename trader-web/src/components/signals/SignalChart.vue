<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import KlineChart from '../KlineChart.vue'
import type { ChartMarker, ChartPriceLine, ChartZone } from '../chart'
import { signalsApi, type ChartBar, type SignalTrack } from '../../api/signals'
import { errMsg, iso } from './format'
import { chartTheme } from '../chart'

/**
 * Bars on the evaluation-date basis plus the signal levels: the bar price scale is adjusted back to the evaluation
 * date (so it still lines up after a later split); marks the evaluation date and the paper ledger's entry / exit,
 * and draws the stop, +1R, chandelier stop, reference target and support zone.
 */
const props = defineProps<{
  symbol: string
  asOf: string
  stop?: number | null
  plusOneR?: number | null
  chandelier?: number | null
  target?: number | null
  zone?: { bottom: number; top: number } | null
  track?: SignalTrack | null
}>()

const bars = ref<ChartBar[]>([])
const error = ref<string | null>(null)
const loading = ref(false)

async function load() {
  loading.value = true
  error.value = null
  try {
    // fetch 300 extra days to warm up MA200; the chart still starts 300 days before the evaluation date
    const from = iso(new Date(new Date(props.asOf).getTime() - 600 * 86_400_000))
    bars.value = await signalsApi.bars(props.symbol, { asOf: props.asOf, from })
  } catch (e) {
    error.value = errMsg(e)
    bars.value = []
  } finally {
    loading.value = false
  }
}

watch(() => [props.symbol, props.asOf], load, { immediate: true })

const markers = computed<ChartMarker[]>(() => {
  const m: ChartMarker[] = [{ time: props.asOf, position: 'belowBar', shape: 'arrowUp', color: '#409eff', text: '判定日' }]
  if (props.track?.entryDate) {
    m.push({ time: props.track.entryDate, position: 'belowBar', shape: 'circle', color: '#e6a23c', text: '入场' })
  }
  if (props.track?.exitDate) {
    m.push({ time: props.track.exitDate, position: 'aboveBar', shape: 'arrowDown', color: '#909399', text: '离场' })
  }
  return m
})

const priceLines = computed<ChartPriceLine[]>(() => {
  const t = chartTheme()
  const l: ChartPriceLine[] = []
  if (props.stop) l.push({ price: props.stop, title: '止损', color: t.down })
  if (props.plusOneR) l.push({ price: props.plusOneR, title: '+1R', color: t.up, dashed: true })
  if (props.chandelier) l.push({ price: props.chandelier, title: '吊灯', color: '#e6a23c', dashed: true })
  if (props.target) l.push({ price: props.target, title: '目标', color: '#909399', dashed: true })
  return l
})

const visibleFrom = computed(() => iso(new Date(new Date(props.asOf).getTime() - 300 * 86_400_000)))
const MA = [20, 50, 200]

const zones = computed<ChartZone[]>(() => (props.zone ? [{ ...props.zone, title: '支撑区', color: '#409eff' }] : []))
</script>

<template>
  <div v-loading="loading">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <KlineChart v-else-if="bars.length" :bars="bars" :height="340" :markers="markers" :price-lines="priceLines" :zones="zones"
                :ma="MA" :visible-from="visibleFrom" ma-key="kline.ma.hidden.signal" ma-hidden-by-default
                :title="`${symbol} 日 K（价格折回 ${asOf} 口径，与当天的价位对齐）`" />
  </div>
</template>
