<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '../components/PageHeader.vue'
import { useLiveAccount } from '../composables/useLiveAccount'
import EquityPanel from '../components/dash/EquityPanel.vue'
import { daysAgoEt, errMsg, fmtEt, isoEt, money, signed, timeEt, trend } from '../lib/format'
import {
  accountApi,
  type AccountSnapshot,
  type AuditReport,
  type HoldingSyncResult,
  type ReconStatus,
  type SnapshotView,
  type SyncAction,
} from '../api/account'

/**
 * 账户页：资金、净值历史、对账。持仓看持仓页。
 *
 * 资金区优先盈透实时（/api/account/live），全部是盈透原值、不做折算；拿不到时退回最近一份收盘快照。
 * 净值历史与对账来自每日美东 18:00 的快照（快照序列里每天都带着当天的对账明细）。
 * 拍快照、持仓同步、账户审计是偶尔才用的维护操作，收在页底折叠区。
 */
const { live, start: startLive, stop: stopLive } = useLiveAccount()
const view = ref<SnapshotView | null>(null)
const series = ref<AccountSnapshot[]>([])
const audit = ref<AuditReport | null>(null)
const plan = ref<HoldingSyncResult | null>(null)
const loaded = ref(false)
const syncing = ref(false)
const error = ref<string | null>(null)

// ---- 名称与状态 ----
const STATUS: Record<ReconStatus, { text: string; type: 'success' | 'warning' | 'danger' }> = {
  OK: { text: '通过', type: 'success' },
  WARN: { text: '提醒', type: 'warning' },
  FAIL: { text: '失败', type: 'danger' },
}
const RECON_NAME: Record<string, string> = {
  identity: '恒等式',
  marketValue: '市值核对',
  holdings: '持仓集合',
  otherAssets: '其他资产',
}
const AUDIT_NAME: Record<string, string> = {
  snapshotExists: '当天快照',
  reconciliation: '对账',
  pricing: '估值取价',
  snapshotJob: '快照作业',
}
const SOURCE_LABEL: Record<string, string> = { BAR: 'K 线收盘', SNAPSHOT: '富途快照价', NONE: '缺价' }
const ACTION_LABEL: Record<SyncAction, string> = {
  ADD: '加入 HOLDING',
  PROMOTE: 'POOL → HOLDING',
  RETURN_TO_POOL: 'HOLDING → POOL',
  REMOVE: '移出池',
}

// ---- 资金：实时优先，退回快照 ----
const useLive = computed(() => {
  const l = live.value
  return !!l && !!l.money && (l.status === 'LIVE' || l.status === 'DISCONNECTED')
})
const snapshot = computed(() => view.value?.snapshot ?? null)
const funds = computed(() => {
  if (useLive.value) {
    const m = live.value!.money!
    const p = live.value!.pnl
    return {
      netLiquidation: m.netLiquidation, totalCash: m.totalCash, availableFunds: m.availableFunds, buyingPower: m.buyingPower,
      excessLiquidity: m.excessLiquidity, grossPositionValue: m.grossPositionValue, stockMarketValue: m.stockMarketValue,
      accruedDividend: m.accruedDividend,
      daily: p?.daily ?? null, unrealized: p?.unrealized ?? null, realized: p?.realized ?? null, pnlReady: !!p,
    }
  }
  const s = snapshot.value
  if (!s) return null
  return {
    netLiquidation: s.netLiquidation, totalCash: s.totalCash, availableFunds: s.availableFunds, buyingPower: s.buyingPower,
    excessLiquidity: s.excessLiquidity, grossPositionValue: s.grossPositionValue, stockMarketValue: s.stockMarketValue,
    accruedDividend: s.accruedDividend,
    daily: null, unrealized: s.unrealizedPnl, realized: s.realizedPnl, pnlReady: true,
  }
})
const accountMask = computed(() => live.value?.accountMask ?? snapshot.value?.accountMask ?? null)
const currency = computed(() => live.value?.currency ?? snapshot.value?.currency ?? null)

// ---- 净值历史 ----
type Range = '90' | '180' | '365' | 'all'
const range = ref<Range>('365')
const RANGES: { value: Range; label: string }[] = [
  { value: '90', label: '3 个月' },
  { value: '180', label: '6 个月' },
  { value: '365', label: '1 年' },
  { value: 'all', label: '全部' },
]
const rangeDays = computed(() => (range.value === 'all' ? null : Number(range.value)))
const inRange = computed(() => {
  const d = rangeDays.value
  const from = d === null ? '' : daysAgoEt(d)
  return series.value.filter((s) => s.asOfDate >= from)
})
/** 表格：最近的在上；较前一日 = 与序列里前一份快照的净值差（含出入金）。 */
const dailyRows = computed(() => {
  const asc = [...series.value].sort((a, b) => a.asOfDate.localeCompare(b.asOfDate))
  const prev = new Map(asc.slice(1).map((s, i) => [s.asOfDate, asc[i]]))
  const from = rangeDays.value === null ? '' : daysAgoEt(rangeDays.value)
  return asc
    .filter((s) => s.asOfDate >= from)
    .map((s) => {
      const p = prev.get(s.asOfDate)
      const change = p && p.netLiquidation !== null && s.netLiquidation !== null ? s.netLiquidation - p.netLiquidation : null
      return { ...s, change }
    })
    .reverse()
})
const expanded = ref<string | null>(null)
function toggle(date: string) {
  expanded.value = expanded.value === date ? null : date
}

// ---- 估值明细（最新快照）----
const valuation = computed(() => {
  const list = view.value?.positions ?? []
  const total = list.reduce((sum, p) => sum + (p.marketValue ?? 0), 0)
  return [...list]
    .sort((a, b) => (b.marketValue ?? 0) - (a.marketValue ?? 0))
    .map((p) => ({ ...p, weight: total > 0 && p.marketValue !== null ? (p.marketValue / total) * 100 : null }))
})
const opened = ref<string[]>([])
const maintenance = ref<string[]>([])

// ---- 取数 ----
async function loadDaily() {
  error.value = null
  try {
    const [s, a, latest] = await Promise.all([
      accountApi.snapshots('2000-01-01', isoEt()),
      accountApi.audit(),
      accountApi.latest().catch((e) => {
        if ((e as { response?: { status?: number } }).response?.status === 404) return null // 还没拍过快照
        throw e
      }),
    ])
    series.value = s
    audit.value = a
    view.value = latest
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    loaded.value = true
  }
}


async function takeSnapshot() {
  try {
    const { jobId } = await accountApi.snapshot()
    ElMessage.success(`账户快照作业 #${jobId} 已提交，几秒后点刷新`)
  } catch (e) {
    ElMessage.error(errMsg(e))
  }
}

async function previewSync() {
  syncing.value = true
  try {
    plan.value = await accountApi.syncHoldings(false)
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    syncing.value = false
  }
}

async function applySync() {
  const p = plan.value
  if (!p || p.plan.blocked || p.plan.changes.length === 0) return
  try {
    await ElMessageBox.confirm(
      p.plan.changes.map((c) => `${ACTION_LABEL[c.action]} ${c.symbol}`).join('；'),
      '按盈透持仓同步池里的 HOLDING',
      { type: 'warning', confirmButtonText: '执行', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  syncing.value = true
  try {
    plan.value = await accountApi.syncHoldings(true)
    if (plan.value.errors.length) ElMessage.warning(plan.value.summary)
    else ElMessage.success(plan.value.summary)
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    syncing.value = false
  }
}

// ---- 刷新节奏：实时见 useLiveAccount（盘中 1 秒），快照与审计 5 分钟；页面切到后台即暂停 ----
let dailyTimer = 0
function start() {
  void loadDaily()
  startLive()
  dailyTimer = window.setInterval(loadDaily, 5 * 60_000)
}
function stop() {
  window.clearInterval(dailyTimer)
  stopLive()
  dailyTimer = 0
}
function onVisibility() {
  if (document.hidden) stop()
  else if (!dailyTimer) start()
}
onMounted(() => {
  start()
  document.addEventListener('visibilitychange', onVisibility)
})
onBeforeUnmount(() => {
  stop()
  document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<template>
  <div class="page">
    <PageHeader title="账户" hint="全部为盈透原值；每个交易日美东 18:00 拍一份快照并对账。持仓明细看持仓页">
      <template #actions>
        <el-button size="small" @click="loadDaily">刷新</el-button>
      </template>
    </PageHeader>
    <el-alert v-if="error" :title="'取数失败：' + error" type="error" show-icon :closable="false" />

    <!-- 资金 -->
    <section class="panel">
      <div class="panel__head">
        <h3>资金</h3>
        <span v-if="accountMask" class="panel__src">{{ accountMask }} · {{ currency ?? '—' }}</span>
        <el-tag v-if="useLive && live!.status === 'LIVE'" size="small" type="success" effect="plain">
          实时 · 资金 {{ timeEt(live!.money!.updatedAt) }} · 盈亏 {{ timeEt(live!.pnl?.updatedAt) }} ET
        </el-tag>
        <el-tag v-else-if="useLive" size="small" type="warning" effect="plain">断线，显示最后收到的数</el-tag>
        <el-tag v-else-if="snapshot" size="small" type="info" effect="plain">
          {{ snapshot.asOfDate }} 收盘快照<template v-if="live?.status === 'WARMING'">（实时正在订阅…）</template>
        </el-tag>
        <span class="panel__grow" />
        <span class="panel__src">账户汇总约 3 分钟推一次，盈亏按变化推</span>
      </div>
      <template v-if="funds">
        <div class="kv-grid funds">
          <div class="kv kv--main"><label>净值</label><b class="num">{{ money(funds.netLiquidation) }}</b></div>
          <div class="kv"><label>现金</label><b class="num">{{ money(funds.totalCash) }}</b></div>
          <div class="kv"><label>可用资金</label><b class="num">{{ money(funds.availableFunds) }}</b></div>
          <div class="kv"><label>购买力</label><b class="num">{{ money(funds.buyingPower) }}</b></div>
          <div class="kv"><label>剩余流动性</label><b class="num">{{ money(funds.excessLiquidity) }}</b></div>
          <div class="kv"><label>持仓总值</label><b class="num">{{ money(funds.grossPositionValue) }}</b></div>
          <div class="kv"><label>股票市值</label><b class="num">{{ money(funds.stockMarketValue) }}</b></div>
          <div class="kv"><label>应计股息</label><b class="num">{{ money(funds.accruedDividend) }}</b></div>
        </div>
        <div class="kv-grid pnl">
          <div class="kv">
            <label>当日盈亏</label>
            <b v-if="useLive && funds.pnlReady" class="num" :class="trend(funds.daily)">{{ signed(funds.daily) }}</b>
            <span v-else class="muted">{{ useLive ? '等待盈透推送…' : '快照不含当日盈亏' }}</span>
          </div>
          <div class="kv">
            <label>浮动盈亏</label>
            <b v-if="funds.pnlReady" class="num" :class="trend(funds.unrealized)">{{ signed(funds.unrealized) }}</b>
            <span v-else class="muted">等待盈透推送…</span>
          </div>
          <div class="kv">
            <label>已实现盈亏</label>
            <b v-if="funds.pnlReady" class="num" :class="trend(funds.realized)">{{ signed(funds.realized) }}</b>
            <span v-else class="muted">等待盈透推送…</span>
          </div>
        </div>
      </template>
      <el-empty v-else :image-size="60" :description="loaded ? '盈透实时不可用，也还没有账户快照' : '加载中…'" />
    </section>

    <!-- 净值历史 -->
    <EquityPanel :series="inRange" :days="rangeDays" :live-nav="useLive ? funds?.netLiquidation ?? null : null" :height="260">
      <template #actions>
        <el-radio-group v-model="range" size="small">
          <el-radio-button v-for="r in RANGES" :key="r.value" :value="r.value">{{ r.label }}</el-radio-button>
        </el-radio-group>
      </template>
    </EquityPanel>

    <section class="panel">
      <div class="panel__head">
        <h3>每日快照</h3>
        <span class="panel__src">美东 18:00 拍；较前一日含出入金，不是交易盈亏；点一行看当天对账</span>
        <span class="panel__grow" />
        <span class="panel__src">{{ dailyRows.length }} 份</span>
      </div>
      <div class="dtable-wrap daily">
        <table class="dtable">
          <thead>
            <tr>
              <th>日期</th><th class="r">净值</th><th class="r">较前一日</th><th class="r">现金</th><th class="r">股票市值</th>
              <th class="r">浮动盈亏</th><th class="r">持仓</th><th class="c">对账</th><th>拍摄时间</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="s in dailyRows" :key="s.asOfDate">
              <tr class="clickable" :class="{ open: expanded === s.asOfDate }" @click="toggle(s.asOfDate)">
                <td><b>{{ s.asOfDate }}</b></td>
                <td class="r num">{{ money(s.netLiquidation) }}</td>
                <td class="r num" :class="trend(s.change)">{{ signed(s.change) }}</td>
                <td class="r num">{{ money(s.totalCash) }}</td>
                <td class="r num">{{ money(s.stockMarketValue) }}</td>
                <td class="r num" :class="trend(s.unrealizedPnl)">{{ signed(s.unrealizedPnl) }}</td>
                <td class="r num">{{ s.positions }}</td>
                <td class="c"><el-tag size="small" :type="STATUS[s.reconStatus].type">{{ STATUS[s.reconStatus].text }}</el-tag></td>
                <td class="muted-cell">{{ fmtEt(s.takenAt) }}</td>
              </tr>
              <tr v-if="expanded === s.asOfDate" class="detail">
                <td colspan="9">
                  <div v-for="c in s.recon" :key="c.name" class="recon-line">
                    <el-tag size="small" :type="STATUS[c.status].type">{{ STATUS[c.status].text }}</el-tag>
                    <b>{{ RECON_NAME[c.name] ?? c.name }}</b>
                    <span>{{ c.detail }}</span>
                  </div>
                </td>
              </tr>
            </template>
            <tr v-if="!dailyRows.length">
              <td colspan="9" class="empty">{{ loaded ? '这个范围内没有快照' : '加载中…' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- 对账（最新快照） -->
    <section v-if="snapshot" class="panel">
      <div class="panel__head">
        <h3>对账</h3>
        <span class="panel__src">{{ snapshot.asOfDate }} 快照：本系统按收盘价估值，与盈透的股票市值、净值互相核对</span>
        <span class="panel__grow" />
        <el-tag size="small" :type="STATUS[snapshot.reconStatus].type">{{ STATUS[snapshot.reconStatus].text }}</el-tag>
      </div>
      <div class="dtable-wrap">
        <table class="dtable">
          <tbody>
            <tr v-for="c in snapshot.recon" :key="c.name">
              <td class="c status-cell"><el-tag size="small" :type="STATUS[c.status].type">{{ STATUS[c.status].text }}</el-tag></td>
              <td class="name-cell"><b>{{ RECON_NAME[c.name] ?? c.name }}</b></td>
              <td class="wrap">{{ c.detail }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <el-collapse v-model="opened" class="fold">
        <el-collapse-item :title="`估值明细（${valuation.length} 条，对账依据；本系统估值 ${money(snapshot.positionValue)}）`" name="valuation">
          <div class="dtable-wrap">
            <table class="dtable">
              <thead>
                <tr>
                  <th>代码</th><th class="r">数量</th><th class="r">成本价</th><th class="r">估值价</th><th>价格来源</th>
                  <th class="r">市值</th><th class="r">占比</th><th class="r">浮动盈亏</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="p in valuation" :key="p.brokerRef">
                  <td>
                    <b>{{ p.symbol }}</b>
                    <el-tag v-if="p.cashEquivalent" size="small" type="info" class="tag">现金工具</el-tag>
                  </td>
                  <td class="r num">{{ p.quantity.toLocaleString(undefined, { maximumFractionDigits: 4 }) }}</td>
                  <td class="r num">{{ money(p.averageCost) }}</td>
                  <td class="r num">{{ money(p.price) }}</td>
                  <td>
                    <el-tag size="small" :type="p.priceSource === 'NONE' ? 'danger' : p.priceSource === 'SNAPSHOT' ? 'warning' : 'info'">
                      {{ SOURCE_LABEL[p.priceSource] }}
                    </el-tag>
                  </td>
                  <td class="r num">{{ money(p.marketValue) }}</td>
                  <td class="r num">{{ p.weight === null ? '—' : p.weight.toFixed(1) + '%' }}</td>
                  <td class="r num" :class="trend(p.unrealizedPnl)">{{ signed(p.unrealizedPnl) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </el-collapse-item>
      </el-collapse>
    </section>

    <!-- 维护 -->
    <el-collapse v-model="maintenance" class="panel maintenance">
      <el-collapse-item name="m">
        <template #title>
          <span class="m-title">维护</span>
          <span class="panel__src m-src">拍快照 · 持仓同步 · 账户审计</span>
          <el-tag v-if="audit" size="small" :type="audit.ok ? 'success' : 'danger'" class="tag">审计 {{ audit.date }} {{ audit.ok ? '通过' : '未通过' }}</el-tag>
        </template>

        <div class="m-block">
          <div class="m-head">
            <b>拍快照</b>
            <el-button size="small" @click="takeSnapshot">拍快照</el-button>
            <span class="muted">只能在快照窗口内（交易日美东 16:15 至次日 04:00）；每天 18:00 会自动拍</span>
          </div>
        </div>

        <div class="m-block">
          <div class="m-head">
            <b>持仓同步（池里的 HOLDING）</b>
            <el-button size="small" :loading="syncing" @click="previewSync">查看同步计划</el-button>
            <el-button size="small" type="primary" :loading="syncing"
                       :disabled="!plan || !!plan.plan.blocked || plan.plan.changes.length === 0 || plan.applied" @click="applySync">
              按计划执行
            </el-button>
            <span class="muted">快照作业每次都会先同步；这里用于盘中买卖后立刻对齐</span>
          </div>
          <template v-if="plan">
            <el-alert v-if="plan.plan.blocked" :title="plan.plan.blocked" type="warning" show-icon :closable="false" />
            <p class="m-text">{{ plan.summary }}</p>
            <div v-if="plan.plan.changes.length" class="tags">
              <el-tag v-for="c in plan.plan.changes" :key="c.action + c.symbol" size="small">{{ ACTION_LABEL[c.action] }} {{ c.symbol }}</el-tag>
            </div>
            <p v-if="plan.plan.untouched.length" class="muted">不动：{{ plan.plan.untouched.join('、') }}</p>
            <el-alert v-for="e in plan.errors" :key="e" :title="e" type="error" :closable="false" />
          </template>
        </div>

        <div v-if="audit" class="m-block">
          <div class="m-head"><b>账户审计 {{ audit.date }}</b><span class="muted">收盘巡检第三段，默认审最近一个已收盘交易日</span></div>
          <div class="dtable-wrap">
            <table class="dtable">
              <tbody>
                <tr v-for="c in audit.checks" :key="c.name">
                  <td class="c status-cell">{{ c.ok ? '✅' : c.critical ? '❌' : '⚠️' }}</td>
                  <td class="name-cell"><b>{{ AUDIT_NAME[c.name] ?? c.name }}</b><span v-if="c.critical" class="muted">（关键）</span></td>
                  <td class="wrap">{{ c.detail }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<style scoped>
.kv--main b { font-size: 18px; }
/* 两行用 auto-fill：列数只取决于宽度，盈亏行的三格与资金行的列对齐（auto-fit 会把三格拉满整行） */
.funds, .pnl { grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); }
.pnl { margin-top: 8px; }
.daily { max-height: 420px; overflow-y: auto; }
.dtable { font-size: 13px; }
.muted-cell { color: var(--el-text-color-secondary); }
tr.open td { background: var(--el-fill-color-light); }
tr.detail td { background: var(--el-fill-color-lighter); white-space: normal; }
.recon-line { display: flex; align-items: baseline; gap: 8px; padding: 3px 0; font-size: 12px; }
.status-cell { width: 60px; }
.name-cell { width: 140px; }
.wrap { white-space: normal; }
.tag { margin-left: 6px; }
.fold { margin-top: 8px; border-top: 0; }
.maintenance { padding-top: 0; padding-bottom: 0; }
.maintenance :deep(.el-collapse-item__header) { gap: 8px; border-bottom: 0; }
.maintenance :deep(.el-collapse-item__wrap) { border-bottom: 0; }
.m-title { font-size: 14px; font-weight: 600; color: var(--el-text-color-primary); }
.m-src { margin-left: 10px; }
.m-block { padding: 8px 0; border-top: 1px solid var(--el-border-color-extra-light); }
.m-block:first-child { border-top: 0; }
.m-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 6px; }
.m-text { margin: 6px 0; font-size: 13px; }
.tags { display: flex; gap: 6px; flex-wrap: wrap; }
</style>
