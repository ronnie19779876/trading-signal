<script setup lang="ts">
import { computed } from 'vue'
import { money, num, signed, timeEt } from '../../lib/format'

/**
 * Holdings, <b>matching the IBKR app</b> (checked field by field against app screenshots on 2026-09-19):
 * <ul>
 *   <li>IBKR live: quantity / average cost from positions, last / prior close from IBKR market data, market value /
 *       daily P&L / unrealized P&L from per-position P&L; change, cost basis and % of portfolio are derived
 *       fields of the app's UI, which the backend supplies with the same definitions;</li>
 *   <li>when live data is unavailable: the latest closing snapshot (close price and market value are that
 *       evening's valuation).</li>
 * </ul>
 * The cost basis and market value columns are hidden (user request on 09-19, for a cleaner table) but rows are still
 * sorted by market value, descending. Prices are shown with two decimals, as in the app.
 */
export interface HoldingRow {
  symbol: string
  quantity: number
  averageCost: number | null
  costBasis: number | null
  price: number | null
  change: number | null
  changePct: number | null
  portfolioPct: number | null
  priceAt: string | null
  priceDelayed: boolean
  marketValue: number | null
  dailyPnl: number | null
  unrealizedPnl: number | null
  cashEquivalent: boolean
  /** Outcome of the day's evaluation (holdings are evaluated too): SIGNAL / BLOCKED_BY_AI are marked next to the symbol */
  outcome: string | null
}

const props = defineProps<{
  rows: HoldingRow[]
  /** LIVE = IBKR live; SNAPSHOT = closing snapshot */
  source: 'LIVE' | 'SNAPSHOT'
  asOf: string | null
  evalDate: string | null
  loaded: boolean
}>()
const emit = defineEmits<{ open: [symbol: string] }>()

const sorted = computed(() => [...props.rows].sort((a, b) => (b.marketValue ?? 0) - (a.marketValue ?? 0)))

/** With an explicit plus sign and two decimals (+2.40 / -0.55). */
function signedNum(v: number | null): string {
  return v === null ? '—' : (v > 0 ? '+' : '') + num(v)
}

function trendOf(v: number | null): string {
  return v === null || v === 0 ? '' : v > 0 ? 'up' : 'down'
}
</script>

<template>
  <section class="panel">
    <div class="panel__head">
      <h3>持仓标的</h3>
      <span v-if="source === 'LIVE'" class="panel__src">盈透实时 · 全部为盈透原值</span>
      <span v-else class="panel__src">盈透 · {{ asOf ? asOf + ' 收盘快照' : '无快照' }}（实时不可用）</span>
      <span class="panel__grow" />
      <span v-if="rows.length" class="panel__src">{{ rows.length }} 只</span>
    </div>
    <div class="dtable-wrap">
      <table class="dtable">
        <thead>
          <tr>
            <th>代码</th><th class="r">数量</th><th class="r">成本价</th>
            <th class="r">{{ source === 'LIVE' ? '最新价' : '收盘价' }}</th><th v-if="source === 'LIVE'" class="r">涨跌</th><th v-if="source === 'LIVE'" class="r">涨跌 %</th>
            <th v-if="source === 'LIVE'" class="r">当日盈亏</th><th class="r">浮动盈亏</th>
            <th v-if="source === 'LIVE'" class="r">占组合</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in sorted" :key="r.symbol" :class="{ clickable: r.outcome !== null, flag: r.outcome === 'SIGNAL' }"
              @click="r.outcome !== null && emit('open', r.symbol)">
            <td>
              <b>{{ r.symbol }}</b>
              <el-tag v-if="r.cashEquivalent" size="small" type="info" class="tag">现金工具</el-tag>
              <el-tooltip v-if="r.outcome === 'SIGNAL' || r.outcome === 'BLOCKED_BY_AI'" placement="top"
                          :content="`${evalDate} 四门全过${r.outcome === 'SIGNAL' ? '，出入场信号' : '，被 AI 否决'}；点一行看判定`">
                <el-tag size="small" :type="r.outcome === 'SIGNAL' ? 'primary' : 'danger'" class="tag">
                  {{ r.outcome === 'SIGNAL' ? '信号' : 'AI 否决' }}
                </el-tag>
              </el-tooltip>
            </td>
            <td class="r num">{{ r.quantity.toLocaleString(undefined, { maximumFractionDigits: 4 }) }}</td>
            <td class="r num">{{ money(r.averageCost) }}</td>
            <td class="r num">
              <el-tooltip v-if="r.priceAt" :content="`盈透行情 · ${timeEt(r.priceAt)}${r.priceDelayed ? '（延迟行情）' : ''}`" placement="top">
                <span>{{ num(r.price) }}</span>
              </el-tooltip>
              <span v-else>{{ num(r.price) }}</span>
              <span v-if="r.priceDelayed" class="stamp">延迟</span>
            </td>
            <td v-if="source === 'LIVE'" class="r num" :class="trendOf(r.change)">{{ signedNum(r.change) }}</td>
            <td v-if="source === 'LIVE'" class="r num" :class="trendOf(r.changePct)">{{ r.changePct === null ? '—' : signedNum(r.changePct) + '%' }}</td>
            <td v-if="source === 'LIVE'" class="r num" :class="trendOf(r.dailyPnl)">{{ signed(r.dailyPnl) }}</td>
            <td class="r num" :class="trendOf(r.unrealizedPnl)">{{ signed(r.unrealizedPnl) }}</td>
            <td v-if="source === 'LIVE'" class="r num">{{ r.portfolioPct === null ? '—' : num(r.portfolioPct) + '%' }}</td>
          </tr>
          <tr v-if="!rows.length">
            <td :colspan="source === 'LIVE' ? 9 : 5" class="empty">{{ loaded ? '无持仓' : '加载中…' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="source === 'LIVE'" class="panel__foot">
      与盈透 App 一致：最新价与前收来自盈透行情，当日盈亏、浮动盈亏来自盈透逐只盈亏；涨跌 = 最新价 − 前收，占组合 = 市值 ÷ 净值，与 App 同口径。按市值从大到小排。
    </p>
    <p v-else class="panel__foot">盈透实时暂不可用，显示最近一份收盘快照。持仓也在每日评估范围里，出信号的标在代码旁。</p>
  </section>
</template>

<style scoped>
.tag { margin-left: 6px; }
.stamp {
  display: inline-block; margin-left: 3px; font-size: 10px; line-height: 14px; padding: 0 3px; border-radius: 3px;
  color: var(--el-color-warning); background: var(--el-color-warning-light-9);
}
tr.flag td { background: var(--el-color-primary-light-9); }
</style>
