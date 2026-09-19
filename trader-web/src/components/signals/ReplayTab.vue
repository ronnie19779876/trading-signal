<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import KlineChart from '../KlineChart.vue'
import type { ChartMarker } from '../chart'
import { signalsApi, type ChartBar, type Replay } from '../../api/signals'
import type { InstrumentView } from '../../api/marketdata'
import { usePager } from '../../composables/usePager'
import { EXIT_LABEL, OUTCOME_LABEL, STATUS_LABEL, daysAgo, errMsg, iso, num, signedR, trend } from './format'

/**
 * 区间回放：逐日判定 + 边沿与冷却（历史没有 AI 结论）+ 纸面交易；只读，不落库。
 * 打开时默认选第一只持仓回放一次，免得一进来是空白。
 */
const props = defineProps<{ universe: InstrumentView[] }>()

const symbol = ref('')
const range = ref<[string, string]>([daysAgo(365 * 3), iso(new Date())])
const stopAtr = ref(2.0)
const half = ref(false)
const replay = ref<Replay | null>(null)
const bars = ref<ChartBar[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

const quick = computed(() => props.universe.filter((u) => u.role === 'HOLDING' || u.role === 'POOL'))

async function run() {
  if (!symbol.value.trim()) return
  loading.value = true
  error.value = null
  try {
    const s = symbol.value.trim().toUpperCase()
    symbol.value = s
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

// 标的列表到了、还没选过就默认第一只持仓
watch(() => props.universe, (u) => {
  if (symbol.value || !u.length) return
  const first = u.find((x) => x.role === 'HOLDING') ?? u.find((x) => x.role === 'POOL')
  if (first) {
    symbol.value = first.symbol
    void run()
  }
}, { immediate: true })

function pick(s: string) {
  symbol.value = s
  void run()
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
const sortedTrades = computed(() => [...trades.value].reverse())
const { page, pageSize, paged, total } = usePager(sortedTrades)
</script>

<template>
  <div v-loading="loading" class="tab">
    <section class="panel">
      <div class="toolbar">
        <el-input v-model="symbol" size="small" placeholder="代码，如 MSFT" style="width: 130px" @keyup.enter="run" />
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" size="small" style="width: 240px" :clearable="false" />
        <span class="muted">止损 ATR 倍数</span>
        <el-input-number v-model="stopAtr" size="small" :min="0.5" :max="10" :step="0.5" style="width: 110px" />
        <el-checkbox v-model="half" size="small">+1R 减半仓（对照）</el-checkbox>
        <el-button size="small" type="primary" :disabled="!symbol.trim()" @click="run">回放</el-button>
      </div>
      <div v-if="quick.length" class="chips">
        <el-check-tag v-for="u in quick" :key="u.symbol" :checked="u.symbol === replay?.symbol" @change="pick(u.symbol)">{{ u.symbol }}</el-check-tag>
      </div>
      <p class="panel__foot">
        用当前规则把一只标的的历史逐日重判一遍，看它在哪些日子会出信号、按纸面规则进出会怎样。只读、不落库；最长 21 年；
        止损倍数与减半仓只改纸面交易的出场，不改判定。一只标的的历史说明不了期望（ARCHITECTURE §18.6）。
      </p>
    </section>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />

    <template v-if="replay">
      <section class="panel">
        <div class="panel__head">
          <h3>{{ replay.symbol }} · {{ replay.from }} ~ {{ replay.to }}</h3>
          <span class="panel__src">{{ replay.exitVariant }}；不计成本</span>
        </div>
        <div class="kv-grid">
          <div class="kv"><label>信号</label><b class="num">{{ replay.outcomeCounts.SIGNAL ?? 0 }}</b></div>
          <div class="kv"><label>纸面交易</label><b class="num">{{ trades.length }}</b></div>
          <div class="kv"><label>已平仓</label><b class="num">{{ closed.length }}</b></div>
          <div class="kv"><label>胜率</label><b class="num">{{ stats ? (stats.win * 100).toFixed(1) + '%' : '—' }}</b></div>
          <div class="kv"><label>每笔 R</label><b class="num" :class="trend(stats?.meanR)">{{ stats ? signedR(stats.meanR) : '—' }}</b></div>
        </div>
        <div class="dist">
          <span class="dist__k">状态</span>
          <el-tag v-for="(v, k) in replay.statusCounts" :key="k" size="small" type="info">{{ STATUS_LABEL[k as keyof typeof STATUS_LABEL] ?? k }} {{ v }}</el-tag>
        </div>
        <div class="dist">
          <span class="dist__k">结果</span>
          <el-tag v-for="(v, k) in replay.outcomeCounts" :key="k" size="small" :type="k === 'SIGNAL' ? 'primary' : 'info'">{{ OUTCOME_LABEL[k as keyof typeof OUTCOME_LABEL] ?? k }} {{ v }}</el-tag>
        </div>
        <KlineChart v-if="bars.length" :bars="bars" :height="320" :markers="markers" :ma="[20, 50, 200]"
                    ma-key="kline.ma.hidden.signal" ma-hidden-by-default
                    :title="`日 K（价格折回 ${replay.to} 口径，最多显示 3 年）`" />
      </section>

      <section class="panel">
        <div class="panel__head"><h3>纸面交易</h3><span class="panel__src">最近的在上；价格为各自判定日口径</span></div>
        <div class="dtable-wrap">
          <table class="dtable">
            <thead>
              <tr>
                <th>判定日</th><th>入场</th><th class="r">止损 / +1R</th><th>离场</th><th class="r">R</th><th class="r">最大浮盈 / 浮亏</th><th class="r">持有天数</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="t in paged" :key="t.signalDate">
                <td>{{ t.signalDate }}</td>
                <td class="num">{{ t.entryDate }} {{ num(t.entry) }}</td>
                <td class="r num">{{ num(t.stop) }} / {{ num(t.plusOneR) }}</td>
                <td class="num">
                  <template v-if="t.exitDate">{{ t.exitDate }} {{ num(t.exit) }}（{{ EXIT_LABEL[t.reason] }}）</template>
                  <template v-else>未平仓</template>
                </td>
                <td class="r num" :class="trend(t.r)">{{ signedR(t.r) }}</td>
                <td class="r num">{{ signedR(t.mfeR) }} / {{ signedR(t.maeR) }}</td>
                <td class="r num">{{ t.barsHeld }}</td>
              </tr>
              <tr v-if="!trades.length"><td colspan="7" class="empty">这段时间没有纸面交易</td></tr>
            </tbody>
          </table>
        </div>
        <div v-if="total > pageSize" class="pager">
          <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="total" :page-sizes="[20, 50, 100]"
                         layout="total, sizes, prev, pager, next" size="small" background />
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.tab { display: flex; flex-direction: column; gap: 12px; }
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.chips { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 8px; }
.chips :deep(.el-check-tag) { padding: 2px 8px; font-size: 12px; }
.dist { margin: 8px 0; display: flex; gap: 6px; flex-wrap: wrap; align-items: center; font-size: 13px; }
.dist__k { color: var(--el-text-color-secondary); width: 36px; }
.dtable { font-size: 13px; }
.pager { display: flex; justify-content: flex-end; margin-top: 10px; }
</style>
