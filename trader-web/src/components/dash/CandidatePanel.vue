<script setup lang="ts">
import { computed } from 'vue'
import type { EvaluationRow, Gate } from '../../api/signals'
import GateDots from '../signals/GateDots.vue'
import Sparkline from './Sparkline.vue'
import { GATE_LABEL, OUTCOME_LABEL, STATUS_LABEL } from '../signals/format'
import { num } from '../../lib/format'

/**
 * 候选标的（池里的 POOL）。摘要，不是入场信号页的复刻：只回答"今天有没有值得看一眼的"。
 * 判据原文在点开的判定抽屉里；排序把出信号的放最前，其次按通过的门数。
 */
export interface CandidateRow {
  symbol: string
  name: string | null
  spark: number[]
  price: number | null
  /** 百分数（1.23 表示 +1.23%） */
  changeRate: number | null
  /** 价格来自富途实时报价；否则是最近一根日 K */
  live: boolean
  /** 实时报价的时段（RTH / PRE / AFTER / OVERNIGHT / CLOSED）；非常规时段在现价旁标出 */
  session: string | null
  priceDate: string | null
  evaluation: EvaluationRow | null
}

const props = defineProps<{ rows: CandidateRow[]; evalDate: string | null; loaded: boolean }>()
const emit = defineEmits<{ open: [symbol: string] }>()

/** 富途报价在非常规时段给的是盘前 / 盘后 / 夜盘价，涨跌相对常规时段收盘——不标出来会被当成当天的涨跌。 */
const SESSION_STAMP: Record<string, string> = { PRE: '盘前', AFTER: '盘后', OVERNIGHT: '夜盘' }

const RANK: Record<string, number> = { SIGNAL: 0, BLOCKED_BY_AI: 1, SUPPRESSED_EDGE: 2, SUPPRESSED_COOLDOWN: 2 }

const sorted = computed(() =>
  [...props.rows].sort((a, b) => {
    const ra = RANK[a.evaluation?.outcome ?? ''] ?? 9
    const rb = RANK[b.evaluation?.outcome ?? ''] ?? 9
    if (ra !== rb) return ra - rb
    const ga = a.evaluation?.gatesPassed ?? -1
    const gb = b.evaluation?.gatesPassed ?? -1
    return gb - ga || a.symbol.localeCompare(b.symbol)
  }),
)

const signals = computed(() => props.rows.filter((r) => r.evaluation?.outcome === 'SIGNAL').length)

function pctText(v: number | null): string {
  return v === null ? '—' : (v > 0 ? '+' : '') + v.toFixed(2) + '%'
}
function trendOf(v: number | null): string {
  return v === null || v === 0 ? '' : v > 0 ? 'up' : 'down'
}
</script>

<template>
  <section class="panel">
    <div class="panel__head">
      <h3>候选标的</h3>
      <span class="panel__src">池 {{ rows.length }} 只<template v-if="evalDate"> · 评估日 {{ evalDate }}</template></span>
      <el-tag v-if="signals" size="small" type="primary" effect="plain">信号 {{ signals }}</el-tag>
      <span class="panel__grow" />
      <router-link to="/signals" class="more-link">入场信号 →</router-link>
    </div>
    <div class="dtable-wrap">
      <table class="dtable">
        <thead>
          <tr>
            <th>代码</th><th>近一年</th><th class="r">现价</th><th class="r">涨跌</th><th class="c">四门</th><th>结果</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in sorted" :key="r.symbol" class="clickable" :class="{ flag: r.evaluation?.outcome === 'SIGNAL' }"
              @click="emit('open', r.symbol)">
            <td>
              <b>{{ r.symbol }}</b>
              <span class="name">{{ r.name ?? '' }}</span>
            </td>
            <td><Sparkline :points="r.spark" /></td>
            <td class="r num">
              {{ num(r.price) }}
              <el-tooltip v-if="!r.live && r.priceDate" :content="`${r.priceDate} 收盘`" placement="top">
                <span class="stamp">收</span>
              </el-tooltip>
              <el-tooltip v-else-if="r.live && SESSION_STAMP[r.session ?? '']" placement="top"
                          :content="`${SESSION_STAMP[r.session ?? '']}价；涨跌相对常规时段收盘`">
                <span class="stamp stamp--ext">{{ SESSION_STAMP[r.session ?? ''] }}</span>
              </el-tooltip>
            </td>
            <td class="r num" :class="trendOf(r.changeRate)">{{ pctText(r.changeRate) }}</td>
            <td class="c"><GateDots :gates="r.evaluation?.gates ?? null" /></td>
            <td>
              <template v-if="r.evaluation">
                <el-tag v-if="r.evaluation.outcome === 'SIGNAL'" size="small" type="primary">信号</el-tag>
                <el-tag v-else-if="r.evaluation.outcome === 'BLOCKED_BY_AI'" size="small" type="danger">AI 否决</el-tag>
                <span v-else-if="r.evaluation.outcome && r.evaluation.outcome !== 'NO_SIGNAL'" class="muted">
                  {{ OUTCOME_LABEL[r.evaluation.outcome] }}
                </span>
                <span v-else-if="r.evaluation.firstBlockingGate" class="muted">
                  卡在{{ GATE_LABEL[r.evaluation.firstBlockingGate as Gate] }}
                </span>
                <el-tag v-else-if="r.evaluation.status !== 'EVALUATED'" size="small" type="warning">
                  {{ STATUS_LABEL[r.evaluation.status] }}
                </el-tag>
              </template>
              <span v-else class="muted">—</span>
            </td>
          </tr>
          <tr v-if="!rows.length">
            <td colspan="6" class="empty">{{ loaded ? '池里没有候选（在行情页加 POOL）' : '加载中…' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <p class="panel__foot">点一行看四门判定。现价标"收"的是最近一根日 K 收盘；标"盘前 / 盘后"的是富途实时的扩展时段价，涨跌相对常规时段收盘；不标的是常规时段实时价。</p>
  </section>
</template>

<style scoped>
.name { margin-left: 6px; font-size: 11px; color: var(--el-text-color-secondary); }
.stamp {
  display: inline-block; margin-left: 3px; font-size: 10px; line-height: 14px; padding: 0 3px; border-radius: 3px;
  color: var(--el-text-color-secondary); background: var(--el-fill-color);
}
.stamp--ext { color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
tr.flag td { background: var(--el-color-primary-light-9); }
.more-link { font-size: 12px; color: var(--el-color-primary); text-decoration: none; }
</style>
