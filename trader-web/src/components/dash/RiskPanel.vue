<script setup lang="ts">
import { computed, ref } from 'vue'
import { accountApi, type AddCheck, type HoldingReview, type HoldingReviewItem } from '../../api/account'
import { errMsg, num } from '../../lib/format'

/**
 * 持仓风险（3.5.0，ARCHITECTURE §29）：每只持仓的趋势状态、下次财报、历史财报反应与压力情景、触发的复核条件；
 * 池里没持有的折叠在下面（只有趋势与历史分布）。底部是加仓检查。
 *
 * <b>只检查用户定的规则、给出数字，不给买卖建议。</b>均线规则在本项目的回测里没有显示出择时价值（§18.6），
 * 复核条件有没有提示价值看 §29 的历史验证。
 */
const props = defineProps<{ review: HoldingReview | null; pending: boolean }>()

const showPool = ref(false)
const holdings = computed(() => (props.review?.items ?? []).filter((i) => i.role === 'HOLDING'))
const poolItems = computed(() => (props.review?.items ?? []).filter((i) => i.role === 'POOL'))
const rows = computed(() => [...holdings.value, ...(showPool.value ? poolItems.value : [])])

const TRIGGER_LABEL: Record<string, string> = {
  BREAK_CONFIRMED: '破位确认',
  DEATH_CROSS: '死叉',
  CHANDELIER_BREACH: '跌破吊灯线',
  EARNINGS_OVER_BUDGET: '财报超预算',
}
const TIMING: Record<string, string> = { PRE_MARKET: '盘前', DURING_MARKET: '盘中', AFTER_MARKET: '盘后', UNKNOWN: '时段未知' }

function stateType(s: string | undefined): 'success' | 'warning' | 'danger' | 'info' {
  if (s === 'UP_STRONG') return 'success'
  if (s === 'UP_WEAK' || s === 'ABOVE_FLAT' || s === 'BELOW') return 'warning'
  if (s === 'BROKEN') return 'danger'
  return 'info'
}

function signedPct(v: number | null | undefined): string {
  return v === null || v === undefined ? '—' : (v > 0 ? '+' : '') + num(v) + '%'
}

function nextText(i: HoldingReviewItem): string {
  const n = i.earnings?.next
  if (!n) return '—'
  return `${n.date.slice(5)} ${TIMING[n.timing] ?? ''}${n.estimated ? '（估计）' : ''}`
}

// ---- 加仓检查 ----
const symbol = ref('')
const shares = ref<number | null>(null)
const checking = ref(false)
const check = ref<AddCheck | null>(null)
const checkError = ref<string | null>(null)
const RULE_LABEL: Record<string, string> = {
  TREND: '1. 趋势门通过', EARNINGS_WINDOW: '2. 不在财报窗口内', LOSS_BUDGET: '3. 加仓后风险在预算内', NO_AVERAGING_DOWN: '4. 不逆势摊低',
}
const STATUS_LABEL: Record<string, string> = { PASS: '满足', FAIL: '不满足', SKIPPED: '未判定' }

async function runCheck() {
  if (!symbol.value.trim() || !shares.value || shares.value <= 0) return
  checking.value = true
  checkError.value = null
  try {
    check.value = await accountApi.addCheck(symbol.value.trim(), shares.value)
  } catch (e) {
    check.value = null
    checkError.value = errMsg(e)
  } finally {
    checking.value = false
  }
}
</script>

<template>
  <section class="panel">
    <div class="panel__head">
      <h3>持仓风险</h3>
      <span v-if="review" class="panel__src">
        财报窗口 {{ review.params.windowDays }} 个交易日 · 压力 = 历史最大跌幅 × {{ review.params.stressMultiple }}
        · 亏损预算 {{ review.params.lossBudgetPct === null ? '未设' : review.params.lossBudgetPct + '% 净值' }}
      </span>
      <span class="panel__grow" />
      <el-checkbox v-if="poolItems.length" v-model="showPool" size="small">含池（{{ poolItems.length }}）</el-checkbox>
    </div>
    <div class="dtable-wrap">
      <table class="dtable">
        <thead>
          <tr>
            <th>代码</th><th>趋势</th><th class="r">距 200 日</th><th>下次财报</th>
            <th class="r">历史财报反应</th><th class="r">压力情景</th><th>复核条件</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="i in rows" :key="i.symbol" :class="{ pool: i.role === 'POOL', flag: i.triggers.length > 0 }">
            <td>
              <b>{{ i.symbol }}</b>
              <span v-if="i.role === 'POOL'" class="muted"> 池</span>
              <span v-else-if="i.weightPct !== null" class="muted"> {{ num(i.weightPct) }}%</span>
            </td>
            <td>
              <span v-if="i.problem" class="muted">{{ i.problem }}</span>
              <el-tooltip v-else-if="i.trend" placement="top"
                          :content="`收盘 ${num(i.trend.close)}（${i.trend.date}）；50 日 ${num(i.trend.smaShort)}、200 日 ${num(i.trend.smaLong)}；距 52 周高点 ${signedPct(i.trend.pctFrom52wHigh)}${i.trend.pnlVsCostPct === null ? '' : '；相对均价 ' + signedPct(i.trend.pnlVsCostPct)}`">
                <el-tag size="small" :type="stateType(i.trend.state)">{{ i.trend.stateLabel }}</el-tag>
              </el-tooltip>
            </td>
            <td class="r num" :class="(i.trend?.pctVsSmaLong ?? 0) < 0 ? 'down' : 'up'">{{ signedPct(i.trend?.pctVsSmaLong) }}</td>
            <td :class="{ warn: i.earnings?.next?.inWindow }">
              {{ nextText(i) }}
              <span v-if="i.earnings?.next" class="muted">· 第 {{ i.earnings.next.tradingDaysAhead }} 日</span>
            </td>
            <td class="r num">
              <el-tooltip v-if="i.earnings?.stats" placement="top"
                          :content="`${i.earnings.stats.samples} 次：涨 ${i.earnings.stats.ups}、跌 ${i.earnings.stats.downs}；≥5% 的 ${i.earnings.stats.bigMoves} 次；最大涨 +${num(i.earnings.stats.maxRisePct)}%；最差跳空 ${signedPct(i.earnings.stats.worstGapPct)}`">
                <span>均 ±{{ num(i.earnings.stats.meanAbsMovePct) }}% · 最大跌 −{{ num(i.earnings.stats.maxDropPct) }}%</span>
              </el-tooltip>
              <span v-else class="muted">—</span>
            </td>
            <td class="r num">
              <template v-if="i.earnings?.scenario?.stressLoss != null">
                <span :class="{ danger: i.earnings.scenario.overBudget }">{{ num(i.earnings.scenario.stressLoss, 0) }}</span>
                <span class="muted">（{{ num(i.earnings.scenario.stressLossPctOfNav) }}%）</span>
              </template>
              <span v-else-if="i.maxWeightPctInBudget !== null" class="muted">仓位上限 {{ num(i.maxWeightPctInBudget) }}%</span>
              <span v-else class="muted">—</span>
            </td>
            <td>
              <el-tooltip v-for="t in i.triggers" :key="t.trigger" :content="t.detail" placement="top">
                <el-tag size="small" type="danger" class="tag">{{ TRIGGER_LABEL[t.trigger] ?? t.trigger }}</el-tag>
              </el-tooltip>
            </td>
          </tr>
          <tr v-if="!rows.length">
            <td colspan="7" class="empty">{{ pending ? '加载中…' : '无持仓' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="check">
      <b>加仓检查</b>
      <el-input v-model="symbol" size="small" placeholder="代码" class="check__sym" @keyup.enter="runCheck" />
      <el-input-number v-model="shares" size="small" :min="0" :step="10" controls-position="right" placeholder="股数" class="check__qty" />
      <el-button size="small" :loading="checking" :disabled="!symbol.trim() || !shares" @click="runCheck">检查</el-button>
      <span v-if="checkError" class="danger">{{ checkError }}</span>
    </div>
    <div v-if="check" class="check__result">
      <div :class="check.allPass ? 'up' : 'danger'"><b>{{ check.symbol }} 加 {{ check.shares }} 股：{{ check.conclusion }}</b>
        <span class="muted">（现价 {{ num(check.price) }}，{{ check.priceDate }}）</span></div>
      <div v-for="r in check.rules" :key="r.rule" class="check__rule">
        <el-tag size="small" :type="r.status === 'PASS' ? 'success' : r.status === 'FAIL' ? 'danger' : 'info'">{{ STATUS_LABEL[r.status] }}</el-tag>
        <span>{{ RULE_LABEL[r.rule] ?? r.rule }}：{{ r.detail }}</span>
      </div>
    </div>

    <p class="panel__foot">
      只检查你设定的规则并给出数字，不是买卖建议。趋势口径与入场信号的趋势门相同（收盘与 200 日均线、均线是否上升）；
      均线规则在本项目回测里没有显示出择时价值（ARCHITECTURE §18.6）；<b>历史验证：三条复核条件成立之后，价格并不比随便哪天更差</b>
      （池与持仓 20 年、全部成分股 2023 年起两组一致，§29.5），它们只是你设定的纪律提醒，不是预测。
      压力情景 = 持仓市值 × 历史最大跌幅 × 倍数；历史最差不是下限（GOOG 2026-10-29 跌 12.3%，超出此前 19 次的最大值）。
    </p>
  </section>
</template>

<style scoped>
.tag { margin-right: 4px; }
.muted { color: var(--el-text-color-secondary); font-size: 11px; margin-left: 4px; }
.warn { color: var(--el-color-warning-dark-2); font-weight: 600; }
.danger { color: var(--el-color-danger); }
tr.pool td { opacity: 0.8; }
tr.flag td:first-child { box-shadow: inset 3px 0 0 var(--el-color-danger); }
.check { display: flex; align-items: center; gap: 8px; padding: 8px 12px 0; font-size: 12px; flex-wrap: wrap; }
.check__sym { width: 90px; }
.check__qty { width: 110px; }
.check__result { padding: 6px 12px 0; font-size: 12px; display: flex; flex-direction: column; gap: 4px; }
.check__rule { display: flex; gap: 6px; align-items: baseline; }
</style>
