<script setup lang="ts">
import { computed, ref } from 'vue'
import KlineChart from '../KlineChart.vue'
import type { ChartMarker } from '../chart'
import { signalsApi, type ChartBar, type Replay } from '../../api/signals'
import { EXIT_LABEL, OUTCOME_LABEL, STATUS_LABEL, daysAgo, errMsg, iso, num, signedR, trend } from './format'

/** 区间回放：逐日判定 + 边沿与冷却（历史没有 AI 结论）+ 纸面交易；只读，不落库。 */
const symbol = ref('')
const range = ref<[string, string]>([daysAgo(365 * 3), iso(new Date())])
const stopAtr = ref(2.0)
const half = ref(false)
const replay = ref<Replay | null>(null)
const bars = ref<ChartBar[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

async function run() {
  if (!symbol.value.trim()) return
  loading.value = true
  error.value = null
  try {
    const s = symbol.value.trim().toUpperCase()
    replay.value = await signalsApi.replay(s, { from: range.value[0], to: range.value[1], stopAtr: stopAtr.value, half: half.value })
    // 图最多 3 年，价格尺度折回区间末日
    const chartFrom = new Date(replay.value.to).getTime() - 3 * 365 * 86_400_000 > new Date(replay.value.from).getTime()
      ? iso(new Date(new Date(replay.value.to).getTime() - 3 * 365 * 86_400_000 + 86_400_000)) : replay.value.from
    bars.value = await signalsApi.bars(s, { from: chartFrom, to: replay.value.to, asOf: replay.value.to })
  } catch (e) {
    error.value = errMsg(e)
    replay.value = null
    bars.value = []
  } finally {
    loading.value = false
  }
}

const trades = computed(() => replay.value?.trades ?? [])
const closed = computed(() => trades.value.filter((t) => t.r !== null))
const stats = computed(() => {
  const c = closed.value
  if (!c.length) return null
  return {
    n: c.length,
    win: c.filter((t) => (t.r ?? 0) > 0).length / c.length,
    meanR: c.reduce((s, t) => s + (t.r ?? 0), 0) / c.length,
  }
})
const markers = computed<ChartMarker[]>(() =>
  (replay.value?.days ?? []).filter((d) => d.outcome === 'SIGNAL')
    .map((d) => ({ time: d.date, position: 'belowBar', shape: 'arrowUp', color: '#409eff', text: '信号' })),
)
</script>

<template>
  <div v-loading="loading" class="tab">
    <div class="toolbar">
      <el-input v-model="symbol" size="small" placeholder="代码，如 MSFT" style="width: 130px" @keyup.enter="run" />
      <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" size="small" style="width: 240px" :clearable="false" />
      <span class="muted">止损 ATR 倍数</span>
      <el-input-number v-model="stopAtr" size="small" :min="0.5" :max="10" :step="0.5" style="width: 110px" />
      <el-checkbox v-model="half" size="small">+1R 减半仓（对照）</el-checkbox>
      <el-button size="small" type="primary" :disabled="!symbol.trim()" @click="run">回放</el-button>
      <span class="muted">最长 21 年；止损倍数与减半仓只改纸面交易的出场，不改判定</span>
    </div>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />

    <template v-if="replay">
      <el-card shadow="never">
        <div class="dist"><b>状态</b>：<el-tag v-for="(v, k) in replay.statusCounts" :key="k" size="small" type="info">{{ STATUS_LABEL[k as keyof typeof STATUS_LABEL] ?? k }} {{ v }}</el-tag></div>
        <div class="dist"><b>结果</b>：<el-tag v-for="(v, k) in replay.outcomeCounts" :key="k" size="small" :type="k === 'SIGNAL' ? 'primary' : 'info'">{{ OUTCOME_LABEL[k as keyof typeof OUTCOME_LABEL] ?? k }} {{ v }}</el-tag></div>
        <div class="dist">
          <b>纸面交易</b>：{{ trades.length }} 笔，已平 {{ closed.length }} 笔
          <template v-if="stats">· 胜率 {{ (stats.win * 100).toFixed(1) }}% · 每笔 <span :class="trend(stats.meanR)">{{ signedR(stats.meanR) }}</span></template>
          <span class="muted">（{{ replay.exitVariant }}；不计成本；一只标的的历史说明不了期望，见 ARCHITECTURE §18.6）</span>
        </div>
        <KlineChart v-if="bars.length" :bars="bars" :height="320" :markers="markers" :title="`${replay.symbol} 日 K（价格折回 ${replay.to} 口径）`" />
      </el-card>

      <el-card shadow="never" header="纸面交易（价格为各自判定日口径）">
        <el-table :data="trades" size="small" max-height="480">
          <el-table-column prop="signalDate" label="判定日" width="100" />
          <el-table-column label="入场" width="160"><template #default="{ row }">{{ row.entryDate }} {{ num(row.entry) }}</template></el-table-column>
          <el-table-column label="止损 / +1R" width="140" align="right"><template #default="{ row }">{{ num(row.stop) }} / {{ num(row.plusOneR) }}</template></el-table-column>
          <el-table-column label="离场" width="210">
            <template #default="{ row }">
              <template v-if="row.exitDate">{{ row.exitDate }} {{ num(row.exit) }}（{{ EXIT_LABEL[row.reason as keyof typeof EXIT_LABEL] }}）</template>
              <template v-else>未平仓</template>
            </template>
          </el-table-column>
          <el-table-column label="R" width="80" align="right"><template #default="{ row }"><span :class="trend(row.r)">{{ signedR(row.r) }}</span></template></el-table-column>
          <el-table-column label="最大浮盈 / 浮亏" width="140"><template #default="{ row }">{{ signedR(row.mfeR) }} / {{ signedR(row.maeR) }}</template></el-table-column>
          <el-table-column prop="barsHeld" label="持有" min-width="60" align="right" />
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.tab { display: flex; flex-direction: column; gap: 12px; }
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.dist { margin: 6px 0; display: flex; gap: 6px; flex-wrap: wrap; align-items: center; font-size: 13px; }
.muted { color: var(--el-text-color-secondary); font-size: 13px; }
.up { color: #ef5350; }
.down { color: #26a69a; }
</style>
