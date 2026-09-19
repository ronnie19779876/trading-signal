<script setup lang="ts">
import { computed } from 'vue'
import { money, num, signed } from '../../lib/format'

/**
 * 持仓标的。两种来源：
 * <ul>
 *   <li>盈透实时（3.0.2）：持仓、市值、当日盈亏、浮盈都来自盈透常驻订阅，秒级；</li>
 *   <li>收盘快照：数量与成本来自最近一份快照；有富途实时报价时现价、当日、浮盈按实时价估算，否则是收盘价。</li>
 * </ul>
 * 默认按市值降序，扫一眼先看到最重的仓位。
 *
 * 刻意不给市值 / 浮盈合计：逐行加起来的数与上面账户区的盈透口径不是同一时刻、同一条计算路径，
 * 同屏两个都叫"市值"却不相等，比没有合计更糟。
 */
export interface HoldingRow {
  symbol: string
  quantity: number
  averageCost: number | null
  price: number | null
  live: boolean
  session: string | null
  /** 当天评估的结果（持仓也在评估范围里）：SIGNAL / BLOCKED_BY_AI 在代码旁标出 */
  outcome: string | null
  /** 当日盈亏金额（只有盈透实时才有） */
  dailyPnl: number | null
  /** 百分数；盈透实时按当日盈亏折算，否则是富途报价或最近一根日 K 的涨跌 */
  dayChange: number | null
  dayChangeDate: string | null
  marketValue: number | null
  unrealizedPnl: number | null
  unrealizedPct: number | null
  weight: number | null
  cashEquivalent: boolean
}

const props = defineProps<{
  rows: HoldingRow[]
  /** LIVE = 盈透实时；SNAPSHOT = 收盘快照（可能叠加富途实时报价） */
  source: 'LIVE' | 'SNAPSHOT'
  asOf: string | null
  evalDate: string | null
  loaded: boolean
}>()
const emit = defineEmits<{ open: [symbol: string] }>()

/** 富途报价在非常规时段给的是盘前 / 盘后 / 夜盘价，涨跌相对常规时段收盘。 */
const SESSION_STAMP: Record<string, string> = { PRE: '盘前', AFTER: '盘后', OVERNIGHT: '夜盘' }

const sorted = computed(() => [...props.rows].sort((a, b) => (b.marketValue ?? 0) - (a.marketValue ?? 0)))
const liveCount = computed(() => props.rows.filter((r) => r.live).length)

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
      <h3>持仓标的</h3>
      <span v-if="source === 'LIVE'" class="panel__src">盈透实时 · 市值与盈亏秒级更新</span>
      <span v-else class="panel__src">
        盈透 · {{ asOf ? asOf + ' 快照' : '无快照' }}<template v-if="liveCount"> · 现价富途实时 {{ liveCount }} 只</template>
      </span>
      <span class="panel__grow" />
      <span v-if="rows.length" class="panel__src">{{ rows.length }} 只 · 合计见上方账户口径</span>
    </div>
    <div class="dtable-wrap">
      <table class="dtable">
        <thead>
          <tr>
            <th>代码</th><th class="r">数量</th><th class="r">成本价</th><th class="r">现价</th><th class="r">当日盈亏</th>
            <th class="r">当日</th><th class="r">浮动盈亏</th><th class="r">盈亏率</th><th class="r">占净值</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in sorted" :key="r.symbol" :class="{ clickable: r.outcome !== null, flag: r.outcome === 'SIGNAL' }"
              @click="r.outcome !== null && emit('open', r.symbol)">
            <td>
              <b>{{ r.symbol }}</b>
              <el-tag v-if="r.cashEquivalent" size="small" type="info" class="cash">现金工具</el-tag>
              <el-tooltip v-if="r.outcome === 'SIGNAL' || r.outcome === 'BLOCKED_BY_AI'" placement="top"
                          :content="`${evalDate} 四门全过${r.outcome === 'SIGNAL' ? '，出入场信号' : '，被 AI 否决'}；点一行看判定`">
                <el-tag size="small" :type="r.outcome === 'SIGNAL' ? 'primary' : 'danger'" class="cash">
                  {{ r.outcome === 'SIGNAL' ? '信号' : 'AI 否决' }}
                </el-tag>
              </el-tooltip>
            </td>
            <td class="r num">{{ r.quantity.toLocaleString() }}</td>
            <td class="r num">{{ money(r.averageCost) }}</td>
            <td class="r num">
              {{ num(r.price) }}
              <span v-if="!r.live && source === 'SNAPSHOT'" class="stamp">收</span>
              <el-tooltip v-else-if="SESSION_STAMP[r.session ?? '']" placement="top"
                          :content="`${SESSION_STAMP[r.session ?? '']}价；当日涨跌与浮盈按这个价算，涨跌相对常规时段收盘`">
                <span class="stamp stamp--ext">{{ SESSION_STAMP[r.session ?? ''] }}</span>
              </el-tooltip>
            </td>
            <td class="r num" :class="trendOf(r.dailyPnl)">{{ r.dailyPnl === null ? '—' : signed(r.dailyPnl) }}</td>
            <td class="r num" :class="trendOf(r.dayChange)">
              <el-tooltip v-if="r.dayChangeDate && !r.live" :content="`${r.dayChangeDate} 日 K 的涨跌`" placement="top">
                <span>{{ pctText(r.dayChange) }}</span>
              </el-tooltip>
              <span v-else>{{ pctText(r.dayChange) }}</span>
            </td>
            <td class="r num" :class="trendOf(r.unrealizedPnl)">{{ signed(r.unrealizedPnl) }}</td>
            <td class="r num" :class="trendOf(r.unrealizedPct)">{{ pctText(r.unrealizedPct) }}</td>
            <td class="r num muted">{{ r.weight === null ? '—' : r.weight.toFixed(2) + '%' }}</td>
          </tr>
          <tr v-if="!rows.length">
            <td colspan="9" class="empty">{{ loaded ? '无持仓' : '加载中…' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="source === 'LIVE'" class="panel__foot">现价 = 盈透市值 ÷ 数量；当日 % = 当日盈亏 ÷ 昨日市值。盘前盘后盈透按扩展时段价重算。持仓也在每日评估范围里，出信号的标在代码旁。</p>
    <p v-else class="panel__foot">现价标"收"的是快照收盘价，当日涨跌取最近一根日 K；有富途实时报价时按实时价，标"盘前 / 盘后"的是扩展时段价。持仓也在每日评估范围里，出信号的标在代码旁。</p>
  </section>
</template>

<style scoped>
.cash { margin-left: 6px; }
.stamp {
  display: inline-block; margin-left: 3px; font-size: 10px; line-height: 14px; padding: 0 3px; border-radius: 3px;
  color: var(--el-text-color-secondary); background: var(--el-fill-color);
}
.muted { font-size: 12px; }
.stamp--ext { color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
tr.flag td { background: var(--el-color-primary-light-9); }
</style>
