<script setup lang="ts">
import { computed } from 'vue'
import type { ScoreView } from '../../api/selection'
import { sectorCn } from '../../lib/sector'
import { fmtEt, pct } from '../../lib/format'
import { EXCLUSION_LABEL, FACTORS, METRICS, parseMetrics, score } from '../../lib/selection'
import RatingTag from './RatingTag.vue'
import ScoreHistoryChart from './ScoreHistoryChart.vue'

/**
 * Score detail for one instrument: the day's rating, composite and four factor scores, the raw values and
 * within-sector percentiles of the 11 metrics, and the score history chart.
 * Raw metric values are ratios shown as percentages; a higher percentile is better (rank within the sector).
 */
const props = defineProps<{ row: ScoreView | null; name: string | null }>()
const visible = defineModel<boolean>({ default: false })

const metrics = computed(() => (props.row ? parseMetrics(props.row) : {}))
const metricRows = computed(() => METRICS.map((m) => {
  const v = metrics.value[m.key]
  return { ...m, value: v?.[0] ?? null, pct: v?.[1] ?? null }
}))
</script>

<template>
  <el-drawer v-model="visible" size="64%" :title="row ? `${row.symbol}${name ? ' · ' + name : ''} · ${row.tradeDate} 量化选股` : '量化选股'" destroy-on-close>
    <div v-if="row" class="drawer">
      <div class="kv-grid">
        <div class="kv"><label>评级</label><b><RatingTag :rating="row.rating" /></b></div>
        <div class="kv"><label>合成分</label><b class="num">{{ score(row.composite) }}</b></div>
        <div class="kv"><label>全市场分位</label><b class="num">{{ score(row.compositePct) }}</b></div>
        <div class="kv"><label>板块</label><b>{{ sectorCn(row.sector) ?? '—' }}</b></div>
        <div class="kv"><label>连续强烈买入</label><b class="num">{{ row.sbStreak ? `${row.sbStreak} 天` : '—' }}</b></div>
        <div v-for="f in FACTORS" :key="f.key" class="kv"><label>{{ f.label }}</label><b class="num">{{ score(row[f.key]) }}</b></div>
      </div>
      <el-alert v-if="row.exclusion" type="warning" :closable="false" show-icon
                :title="`当天不参与打分：${EXCLUSION_LABEL[row.exclusion]}`" :description="row.exclusionDetail ?? ''" />
      <el-alert v-else-if="!row.rating" type="info" :closable="false" show-icon
                title="当天没有评级：四个因子不齐（某个因子的适用指标不到一半有值）" />

      <section class="panel">
        <div class="panel__head">
          <h3>指标明细</h3>
          <span class="panel__src">原值 · 板块内百分位（同板块、该指标有值的合格标的内排名，越大越好；不足 5 只不排）</span>
        </div>
        <table class="dtable">
          <thead><tr><th>因子</th><th>指标</th><th class="r">原值</th><th style="width: 40%">板块内百分位</th><th>口径</th></tr></thead>
          <tbody>
            <tr v-for="m in metricRows" :key="m.key">
              <td class="muted">{{ m.factor }}</td>
              <td><b>{{ m.label }}</b></td>
              <td class="r num">{{ m.value === null ? '—' : pct(m.value, 1) }}</td>
              <td>
                <div v-if="m.pct !== null" class="pbar"><span class="pbar__fill" :style="{ width: `${m.pct}%` }" /><span class="pbar__val num">{{ m.pct.toFixed(0) }}</span></div>
                <span v-else class="muted">{{ m.value === null ? '没有数据' : row.exclusion ? '当天被排除，不参与排名' : '不排（板块内有值的不足 5 只）' }}</span>
              </td>
              <td class="muted hint wrap">{{ m.hint }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <ScoreHistoryChart :symbol="row.symbol" :as-of="row.tradeDate" />

      <p class="muted foot">模型 F1 · 输入指纹 {{ row.fingerprint }} · 计算于 {{ fmtEt(row.computedAt) }}（美东）</p>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer { display: flex; flex-direction: column; gap: 12px; }
.dtable { font-size: 13px; }
.wrap { white-space: normal; }
.pbar { position: relative; height: 14px; border-radius: 3px; background: var(--el-fill-color); overflow: hidden; }
.pbar__fill { position: absolute; inset: 0 auto 0 0; background: var(--el-color-primary); opacity: 0.6; }
.pbar__val { position: absolute; right: 6px; top: -1px; font-size: 11px; }
.hint { font-size: 12px; }
.foot { font-size: 12px; margin: 0; }
</style>
