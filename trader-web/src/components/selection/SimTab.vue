<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { simApi, type SimEvent, type SimOverview, type SimTrade, type SimWeekly } from '../../api/selection'
import { errMsg, fmtEt, money, pct, signed, signedR, trend } from '../../lib/format'
import SimEquityChart from './SimEquityChart.vue'
import SimCriteriaPanel from './SimCriteriaPanel.vue'

/**
 * Simulated trading (ARCHITECTURE §26): a paper account that tests whether the selection works; it places no
 * orders. Rules version sim-v1: equal weight, stop capped at 10%, +2R when there is no resistance zone, zero costs.
 * Stops and chandelier stops close automatically (decided on the close, filled at the close); take-profit only
 * alerts and a human clicks to close.
 * Judge the selection by excess return over SPY and by sample size, not by absolute P&L -- every summary figure on
 * the page carries its n.
 */
const props = defineProps<{ overview: SimOverview | null; names: Map<string, string | null> }>()
const emit = defineEmits<{ changed: [] }>()

const trades = ref<SimTrade[]>([])
const weeklies = ref<SimWeekly[]>([])
const events = ref<SimEvent[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    const [t, w, e] = await Promise.all([simApi.trades(), simApi.weeklies(), simApi.events(100)])
    trades.value = t
    weeklies.value = w
    events.value = e
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)
defineExpose({ load })

const active = computed(() => trades.value.filter((t) => t.status === 'PENDING_ENTRY' || t.status === 'OPEN'))
const closed = computed(() => trades.value.filter((t) => t.status === 'CLOSED').sort((a, b) => (b.exitDate ?? '').localeCompare(a.exitDate ?? '')))
const cancelled = computed(() => trades.value.filter((t) => t.status === 'CANCELLED'))

/** Summary of closed trades: n is reported as-is, no significance test. Trades without a return count as 0 in meanReturn. */
const totals = computed(() => {
  const c = closed.value
  const wins = c.filter((t) => (t.pnl ?? 0) > 0).length
  const withExcess = c.filter((t) => t.excessReturn !== null)
  const manual = c.filter((t) => t.exitReason === 'TAKE_PROFIT' || t.exitReason === 'MANUAL')
  const paired = manual.filter((t) => t.shadowStatus === 'CLOSED' && t.shadowPnl !== null)
  const mean = (xs: number[]) => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null)
  return {
    n: c.length,
    wins,
    pnl: c.reduce((a, t) => a + (t.pnl ?? 0), 0),
    meanReturn: mean(c.map((t) => t.returnPct ?? 0)),
    meanExcess: mean(withExcess.map((t) => t.excessReturn as number)),
    withExcess: withExcess.length,
    manual: manual.length,
    paired: paired.length,
    manualMinusShadow: paired.length ? paired.reduce((a, t) => a + (t.pnl ?? 0) - (t.shadowPnl ?? 0), 0) : null,
  }
})

const STATUS_LABEL: Record<string, string> = { PENDING_ENTRY: '待成交', OPEN: '持有', CLOSED: '已平仓', CANCELLED: '已取消' }
const REASON_LABEL: Record<string, string> = { STOP: '止损', CHANDELIER: '吊灯止损', TAKE_PROFIT: '止盈（手动）', MANUAL: '手动' }
const LEG_LABEL: Record<string, string> = { ATR: '收盘 − 2×ATR', ZONE: '支撑区底 − 0.5×ATR', CAP: '封顶 10%' }
const name = (s: string) => props.names.get(s)
const n4 = (v: number | null | undefined) => (v === null || v === undefined ? '—' : Number(v.toFixed(4)).toString())

function stateText(t: SimTrade) {
  if (t.closeRequestedAt) return `待平仓（${t.plannedExitDate} 开盘）`
  if (t.status === 'PENDING_ENTRY') return `待成交（${t.plannedEntryDate} 起第一个开盘）`
  return STATUS_LABEL[t.status]
}

async function close(t: SimTrade) {
  const pending = t.status === 'PENDING_ENTRY'
  const tp = t.targetHitDate !== null
  try {
    const { value } = await ElMessageBox.prompt(
      pending
        ? `撤销 ${t.symbol}（还没入场）：如果入场那个开盘已经过了，会改记平仓请求，在下一个开盘平。`
        : `${t.symbol} ${tp ? `已于 ${t.targetHitDate} 触及止盈目标 ${n4(t.target)}，` : ''}按点击之后的第一个开盘价平仓，记为「${tp ? '止盈' : '手动'}」。` +
          `影子变体会继续只按止损走，用来比较这次平仓的得失。可以写一句理由（选填）：`,
      pending ? '撤销模拟交易' : '模拟平仓',
      { confirmButtonText: pending ? '撤销' : '平仓', cancelButtonText: '取消', inputPlaceholder: '理由（选填）', type: 'warning' },
    )
    await simApi.close(t.id, value || undefined)
    ElMessage.success(pending ? '已提交' : '已记下平仓请求，下一个开盘成交（收盘后的作业里落账）')
    await load()
    emit('changed')
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(errMsg(e))
  }
}

// ---- weekly reports: the list has no body; it is fetched when a week is opened
const opened = ref<string | null>(null)
const report = ref<SimWeekly | null>(null)
const reportLoading = ref(false)
async function openWeek(w: SimWeekly) {
  if (opened.value === w.weekEnd) {
    opened.value = null
    return
  }
  opened.value = w.weekEnd
  report.value = null
  reportLoading.value = true
  try {
    report.value = await simApi.weekly(w.weekEnd)
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    reportLoading.value = false
  }
}
const r = computed(() => report.value?.report ?? null)

const ruleKeys: [string, string][] = [
  ['sizing', '仓位'], ['entry', '入场'], ['stop', '初始止损'], ['trailing', '跟踪止损'], ['stopExit', '止损平仓'],
  ['target', '止盈'], ['manualExit', '手动平仓'], ['timeStop', '时间止损'], ['costs', '成本与口径'],
]
const collapse = ref<string[]>([])
</script>

<template>
  <div v-loading="loading" class="tab">
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />

    <section v-if="overview" class="panel">
      <div class="panel__head">
        <h3>模拟账户</h3>
        <span class="panel__src">规则 {{ overview.account.rulesVersion }} · 不下单，只用收盘后的日 K 记账 · 价格不含分红、不计成本</span>
      </div>
      <div class="kv-grid">
        <div class="kv"><label>初始资金</label><b class="num">{{ money(overview.account.initialCapital) }}</b></div>
        <div class="kv"><label>权益</label><b class="num">{{ money(overview.equity) }}</b></div>
        <div class="kv"><label>现金 / 持仓市值</label><b class="num">{{ money(overview.cash) }} / {{ money(overview.positionsValue) }}</b></div>
        <div class="kv"><label>已实现 / 浮动</label>
          <b class="num"><span :class="trend(overview.realized)">{{ signed(overview.realized) }}</span> /
            <span :class="trend(overview.unrealized)">{{ signed(overview.unrealized) }}</span></b></div>
        <div class="kv"><label>累计收益（自 {{ overview.startDate ?? '—' }}）</label>
          <b class="num" :class="trend(overview.totalReturn)">{{ pct(overview.totalReturn) }}</b></div>
        <div class="kv"><label>年化收益{{ overview.criteria.days !== null ? `（已运行 ${overview.criteria.days} 天）` : '' }}</label>
          <b class="num" :class="trend(overview.criteria.annualized)">{{ pct(overview.criteria.annualized) }}</b>
          <span v-if="overview.criteria.verdict === 'OBSERVING'" class="muted sub">未满 12 个月，仅供参考</span></div>
        <div class="kv"><label>SPY 同期</label><b class="num" :class="trend(overview.spySinceStart)">{{ pct(overview.spySinceStart) }}</b></div>
        <div class="kv"><label>进行中 / 上限</label><b class="num">{{ overview.open + overview.pending }} / {{ overview.candidates.slots }}</b></div>
        <div class="kv"><label>止盈提示</label><b class="num" :class="{ warn: overview.targetAlerts > 0 }">{{ overview.targetAlerts }}</b></div>
      </div>
      <p class="muted note">
        持仓按各笔最近一根收盘估值（最早的截至 {{ overview.markedThrough ?? '—' }}）。加入在「评分榜」最新评分日的前 {{ overview.candidates.top.length }} 名上点「模拟」。
      </p>
      <SimCriteriaPanel :criteria="overview.criteria" />
      <el-collapse v-model="collapse">
        <el-collapse-item name="rules" title="规则（sim-v1，写死在代码里，改规则就换版本）">
          <table class="dtable">
            <tbody>
              <tr v-for="[k, label] in ruleKeys" :key="k"><td style="width: 100px" class="muted">{{ label }}</td><td class="wrap">{{ overview.rules[k] }}</td></tr>
            </tbody>
          </table>
        </el-collapse-item>
      </el-collapse>
    </section>

    <section class="panel">
      <div class="panel__head"><h3>进行中 {{ active.length }} 笔</h3><span class="panel__src">价格为评分日口径；止盈只提示，点「平仓」才平</span></div>
      <div class="dtable-wrap">
        <table class="dtable">
          <thead>
            <tr>
              <th>代码</th><th>状态</th><th>评分日 · 名次</th><th>入场</th><th class="r">股数</th><th class="r">止损</th>
              <th class="r">止盈目标</th><th class="r">最近收盘</th><th class="r">浮动盈亏</th><th class="r">收益</th><th class="r">SPY</th>
              <th class="r">超额</th><th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in active" :key="t.id">
              <td><b>{{ t.symbol }}</b><span v-if="name(t.symbol)" class="name">{{ name(t.symbol) }}</span></td>
              <td class="wrap">{{ stateText(t) }}
                <el-tag v-if="t.targetHitDate && !t.closeRequestedAt" size="small" type="warning" effect="dark">可止盈 · {{ t.targetHitDate }}</el-tag>
              </td>
              <td class="num">{{ t.scoreDate }} · #{{ t.scoreRank }}</td>
              <td class="num">{{ t.entryDate ? `${t.entryDate} · ${n4(t.entryPrice)}` : '—' }}</td>
              <td class="r num">{{ t.shares ?? '—' }}</td>
              <td class="r num">
                <span :title="LEG_LABEL[t.stopLeg]">{{ n4(t.chandelierActive ? t.activeStop : t.stop) }}</span>
                <span class="muted sub">{{ t.chandelierActive ? '吊灯' : LEG_LABEL[t.stopLeg] }}</span>
              </td>
              <td class="r num">{{ n4(t.target) }}<span v-if="t.targetSource" class="muted sub">{{ t.targetSource === 'RESISTANCE' ? '压力区底' : '+2R' }}</span></td>
              <td class="r num">{{ n4(t.lastClose) }}<span v-if="t.lastCloseDate" class="muted sub">{{ t.lastCloseDate }}</span></td>
              <td class="r num" :class="trend(t.unrealizedPnl)">{{ signed(t.unrealizedPnl) }}</td>
              <td class="r num" :class="trend(t.lastClose && t.entryPrice ? t.lastClose - t.entryPrice : null)">
                {{ t.lastClose && t.entryPrice ? pct(t.lastClose / t.entryPrice - 1) : '—' }}</td>
              <td class="r num">{{ pct(t.spyReturn) }}</td>
              <td class="r num" :class="trend(t.excessReturn)">{{ pct(t.excessReturn) }}</td>
              <td class="c">
                <el-button v-if="!t.closeRequestedAt" size="small" :type="t.targetHitDate ? 'warning' : 'default'" @click="close(t)">
                  {{ t.status === 'PENDING_ENTRY' ? '撤销' : '平仓' }}</el-button>
              </td>
            </tr>
            <tr v-if="!active.length"><td colspan="13" class="empty">没有进行中的模拟交易</td></tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="panel">
      <div class="panel__head">
        <h3>已平仓 {{ totals.n }} 笔</h3>
        <span class="panel__src">影子 = 同一笔不做手动平仓、只按止损走的结果，用来配对比较手动止盈的得失</span>
      </div>
      <div class="kv-grid">
        <div class="kv"><label>胜 / 负（n）</label><b class="num">{{ totals.wins }} / {{ totals.n - totals.wins }}（{{ totals.n }}）</b></div>
        <div class="kv"><label>已实现合计</label><b class="num" :class="trend(totals.pnl)">{{ signed(totals.pnl) }}</b></div>
        <div class="kv"><label>平均收益（n = {{ totals.n }}）</label><b class="num" :class="trend(totals.meanReturn)">{{ pct(totals.meanReturn) }}</b></div>
        <div class="kv"><label>平均对 SPY 超额（n = {{ totals.withExcess }}）</label><b class="num" :class="trend(totals.meanExcess)">{{ pct(totals.meanExcess) }}</b></div>
        <div class="kv"><label>手动平仓 − 影子（配对 {{ totals.paired }} / 手动 {{ totals.manual }}）</label>
          <b class="num" :class="trend(totals.manualMinusShadow)">{{ signed(totals.manualMinusShadow) }}</b></div>
      </div>
      <div class="dtable-wrap">
        <table class="dtable">
          <thead>
            <tr>
              <th>代码</th><th>入场</th><th>平仓</th><th>原因</th><th class="r">股数</th><th class="r">盈亏</th><th class="r">收益</th>
              <th class="r">R</th><th class="r">SPY</th><th class="r">超额</th><th class="r">影子</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in closed" :key="t.id">
              <td><b>{{ t.symbol }}</b><span v-if="name(t.symbol)" class="name">{{ name(t.symbol) }}</span></td>
              <td class="num">{{ t.entryDate }} · {{ n4(t.entryPrice) }}</td>
              <td class="num">{{ t.exitDate }} · {{ n4(t.exitPrice) }}</td>
              <td>{{ t.exitReason ? REASON_LABEL[t.exitReason] : '—' }}<span v-if="t.closeNote" class="muted sub">{{ t.closeNote }}</span></td>
              <td class="r num">{{ t.shares }}</td>
              <td class="r num" :class="trend(t.pnl)">{{ signed(t.pnl) }}</td>
              <td class="r num" :class="trend(t.returnPct)">{{ pct(t.returnPct) }}</td>
              <td class="r num">{{ signedR(t.rMultiple) }}</td>
              <td class="r num">{{ pct(t.spyReturn) }}</td>
              <td class="r num" :class="trend(t.excessReturn)">{{ pct(t.excessReturn) }}</td>
              <td class="r num">
                <template v-if="t.shadowStatus === 'CLOSED'"><span :class="trend(t.shadowPnl)">{{ signed(t.shadowPnl) }}</span></template>
                <template v-else>持有中<span class="muted sub">最近收盘 {{ n4(t.shadowLastClose) }}</span></template>
              </td>
            </tr>
            <tr v-if="!closed.length"><td colspan="11" class="empty">还没有平仓</td></tr>
          </tbody>
        </table>
      </div>
    </section>

    <SimEquityChart v-if="overview?.startDate" :initial="overview.account.initialCapital" />

    <section class="panel">
      <div class="panel__head"><h3>周报</h3><span class="panel__src">每周六美东 10:00 生成快照，生成后不变；点一行看明细</span></div>
      <table class="dtable">
        <thead><tr><th>周</th><th class="r">周末权益</th><th class="r">本周</th><th class="r">SPY 本周</th><th class="r">本周平仓</th><th>生成于</th></tr></thead>
        <tbody>
          <template v-for="w in weeklies" :key="w.weekEnd">
            <tr class="clickable" @click="openWeek(w)">
              <td class="num">{{ w.weekStart }} ~ {{ w.weekEnd }}</td>
              <td class="r num">{{ money(w.equityEnd) }}</td>
              <td class="r num" :class="trend(w.weekReturn)">{{ pct(w.weekReturn) }}</td>
              <td class="r num" :class="trend(w.spyWeekReturn)">{{ pct(w.spyWeekReturn) }}</td>
              <td class="r num">{{ w.closedTrades }}</td>
              <td class="muted">{{ fmtEt(w.generatedAt) }}</td>
            </tr>
            <tr v-if="opened === w.weekEnd">
              <td colspan="6" class="week" v-loading="reportLoading">
                <template v-if="r">
                  <div class="kv-grid">
                    <div class="kv"><label>周初 → 周末权益</label><b class="num">{{ money(r.equityStart) }} → {{ money(r.equityEnd) }}</b></div>
                    <div class="kv"><label>本周盈亏</label><b class="num" :class="trend(r.weekPnl)">{{ signed(r.weekPnl) }}（{{ pct(r.weekReturn) }}）</b></div>
                    <div class="kv"><label>SPY / 范围等权（{{ r.universeCount }} 只）</label>
                      <b class="num">{{ pct(r.spyWeekReturn) }} / {{ pct(r.universeWeekReturn) }}</b></div>
                    <div class="kv"><label>累计（自 {{ r.accountStart }}）/ SPY 同期</label><b class="num">{{ pct(r.cumulativeReturn) }} / {{ pct(r.spySinceStart) }}</b></div>
                    <div class="kv"><label>年化（截至周末）</label><b class="num">{{ pct(r.criteria?.annualized) }}</b></div>
                    <div class="kv"><label>最大回撤</label><b class="num">{{ pct(r.maxDrawdown) }}</b></div>
                    <div class="kv"><label>本周已实现 / 周末浮动</label><b class="num">{{ signed(r.realizedWeek) }} / {{ signed(r.unrealizedEnd) }}</b></div>
                    <div class="kv"><label>累计已平仓 胜/负（n）</label><b class="num">{{ r.allClosed.wins }} / {{ r.allClosed.losses }}（{{ r.allClosed.n }}）</b></div>
                    <div class="kv"><label>累计平均超额（n = {{ r.allClosed.withExcess }}）</label><b class="num">{{ pct(r.allClosed.meanExcess) }}</b></div>
                  </div>
                  <h4>本周平仓 {{ r.closedThisWeek.length }} 笔</h4>
                  <table v-if="r.closedThisWeek.length" class="dtable">
                    <thead><tr><th>代码</th><th>入场</th><th>平仓</th><th>原因</th><th class="r">盈亏</th><th class="r">收益</th><th class="r">SPY</th><th class="r">超额</th><th class="r">影子</th></tr></thead>
                    <tbody>
                      <tr v-for="x in r.closedThisWeek" :key="x.id">
                        <td><b>{{ x.symbol }}</b></td><td class="num">{{ x.entryDate }} · {{ n4(x.entry) }}</td><td class="num">{{ x.exitDate }} · {{ n4(x.exit) }}</td>
                        <td>{{ x.reason ? REASON_LABEL[x.reason] : '—' }}</td>
                        <td class="r num" :class="trend(x.pnl)">{{ signed(x.pnl) }}</td><td class="r num">{{ pct(x.returnPct) }}</td>
                        <td class="r num">{{ pct(x.spyReturn) }}</td><td class="r num" :class="trend(x.excessReturn)">{{ pct(x.excessReturn) }}</td>
                        <td class="r num">{{ x.shadowStatus === 'CLOSED' ? signed(x.shadowPnl) : '持有中' }}</td>
                      </tr>
                    </tbody>
                  </table>
                  <h4>周末持有 {{ r.openPositions.length }} 笔（按周末收盘估值）</h4>
                  <table v-if="r.openPositions.length" class="dtable">
                    <thead><tr><th>代码</th><th>入场</th><th>周末收盘</th><th class="r">浮动</th><th class="r">收益</th><th class="r">SPY</th><th class="r">超额</th><th>止盈提示</th></tr></thead>
                    <tbody>
                      <tr v-for="x in r.openPositions" :key="x.id">
                        <td><b>{{ x.symbol }}</b></td><td class="num">{{ x.entryDate }} · {{ n4(x.entry) }}</td><td class="num">{{ x.markDate }} · {{ n4(x.mark) }}</td>
                        <td class="r num" :class="trend(x.unrealizedPnl)">{{ signed(x.unrealizedPnl) }}</td><td class="r num">{{ pct(x.returnPct) }}</td>
                        <td class="r num">{{ pct(x.spyReturn) }}</td><td class="r num" :class="trend(x.excessReturn)">{{ pct(x.excessReturn) }}</td>
                        <td>{{ x.targetHitDate ?? '—' }}</td>
                      </tr>
                    </tbody>
                  </table>
                  <SimCriteriaPanel :criteria="r.criteria" />
                  <ul class="caveats"><li v-for="c in r.caveats" :key="c">{{ c }}</li></ul>
                  <p class="muted note">数据算到 {{ r.generatedThrough }} 时生成。</p>
                </template>
              </td>
            </tr>
          </template>
          <tr v-if="!weeklies.length"><td colspan="6" class="empty">还没有周报（第一笔入场后的那个周六生成）</td></tr>
        </tbody>
      </table>
    </section>

    <el-collapse>
      <el-collapse-item v-if="cancelled.length" :title="`已取消 ${cancelled.length} 笔`">
        <table class="dtable">
          <thead><tr><th>代码</th><th>评分日</th><th>原因</th></tr></thead>
          <tbody><tr v-for="t in cancelled" :key="t.id"><td><b>{{ t.symbol }}</b></td><td class="num">{{ t.scoreDate }}</td><td class="wrap muted">{{ t.cancelReason }}</td></tr></tbody>
        </table>
      </el-collapse-item>
      <el-collapse-item :title="`操作留痕（最近 ${events.length} 条）`">
        <table class="dtable">
          <tbody>
            <tr v-for="e in events" :key="e.id">
              <td class="muted nowrap">{{ fmtEt(e.at) }}</td><td class="nowrap">{{ e.kind }}</td><td class="wrap">{{ e.detail }}</td>
            </tr>
          </tbody>
        </table>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<style scoped>
.tab { display: flex; flex-direction: column; gap: 12px; }
.dtable { font-size: 13px; }
.name { margin-left: 8px; font-size: 12px; color: var(--el-text-color-secondary); }
.sub { display: block; font-size: 11px; }
.wrap { white-space: normal; }
.nowrap { white-space: nowrap; }
.warn { color: var(--el-color-warning); }
.note { font-size: 12px; margin: 6px 0; }
.week { background: var(--el-fill-color-lighter); padding: 12px; }
.week h4 { margin: 12px 0 6px; font-size: 13px; }
.caveats { margin: 10px 0 0; padding-left: 18px; font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.7; }
</style>
