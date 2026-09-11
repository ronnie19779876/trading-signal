<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  addToPool, backfillPending, cancelJob, getBars, getCoverage, getJobs, getPool, refreshRehab, refreshUniverse, removeFromPool,
  runIncrement, syncUniverse, type Adjust, type CoverageView, type DailyBar, type InstrumentView, type JobRun, type RunningJob,
} from '../api/marketdata'
import { getQuoteStatus, pauseQuotes, quoteStreamUrl, reconcileQuotes, resumeQuotes, type Quote, type QuoteStatus } from '../api/quotes'
import KlineChart from '../components/KlineChart.vue'

const coverage = ref<CoverageView | null>(null)
const running = ref<RunningJob | null>(null)
const jobs = ref<JobRun[]>([])
const pool = ref<InstrumentView[]>([])
const error = ref<string | null>(null)
let timer: ReturnType<typeof setInterval> | null = null

const newSymbol = ref('')
const newRole = ref<'POOL' | 'HOLDING' | 'BENCHMARK'>('POOL')

const barSymbol = ref('AAPL')
const barFrom = ref(new Date(Date.now() - 90 * 86400000).toISOString().slice(0, 10))
const barTo = ref(new Date().toISOString().slice(0, 10))
const barAdjust = ref<Adjust>('none')
const bars = ref<DailyBar[]>([])
const barsLoading = ref(false)

// ---- 实时报价（SSE）----
const quotes = ref<Map<string, Quote>>(new Map())
const quoteStatus = ref<QuoteStatus | null>(null)
const sseState = ref<'closed' | 'connecting' | 'open'>('closed')
let es: EventSource | null = null

function quoteList(): Quote[] {
  return [...quotes.value.values()].sort((a, b) => a.instrument.symbol.localeCompare(b.instrument.symbol))
}

function openStream() {
  if (es) return
  sseState.value = 'connecting'
  es = new EventSource(quoteStreamUrl)
  es.onopen = () => (sseState.value = 'open')
  es.onerror = () => (sseState.value = 'connecting')
  es.addEventListener('quotes', (e) => {
    const list = JSON.parse((e as MessageEvent).data) as Quote[]
    const m = new Map(quotes.value)
    for (const q of list) m.set(q.instrument.symbol, q)
    quotes.value = m
  })
  es.addEventListener('status', (e) => {
    quoteStatus.value = JSON.parse((e as MessageEvent).data) as QuoteStatus
  })
}

function closeStream() {
  es?.close()
  es = null
  sseState.value = 'closed'
}

async function quoteAction(label: string, action: () => Promise<unknown>) {
  try {
    await action()
    ElMessage.success(label + '完成')
    quoteStatus.value = await getQuoteStatus()
  } catch (e) {
    ElMessage.error(label + '失败：' + msg(e))
  }
}

function selectSymbol(symbol: string) {
  barSymbol.value = symbol
  loadBars()
}

function msg(e: unknown): string {
  const anyE = e as { response?: { data?: { message?: string } }; message?: string }
  return anyE?.response?.data?.message ?? anyE?.message ?? String(e)
}

async function refresh() {
  try {
    const [c, j, p] = await Promise.all([getCoverage(), getJobs(15), getPool()])
    coverage.value = c
    running.value = j.running && 'id' in j.running ? (j.running as RunningJob) : null
    jobs.value = j.recent
    pool.value = p
    error.value = null
  } catch (e) {
    error.value = msg(e)
  }
}

async function run(label: string, action: () => Promise<{ jobId: number }>) {
  try {
    const r = await action()
    ElMessage.success(`${label}：作业 #${r.jobId} 已开始`)
    await refresh()
  } catch (e) {
    ElMessage.error(`${label}失败：` + msg(e))
  }
}

async function confirmRun(label: string, hint: string, action: () => Promise<{ jobId: number }>) {
  try {
    await ElMessageBox.confirm(hint, label, { confirmButtonText: '开始', cancelButtonText: '取消', type: 'warning' })
  } catch {
    return
  }
  await run(label, action)
}

async function add() {
  const s = newSymbol.value.trim().toUpperCase()
  if (!s) return
  try {
    const r = await addToPool(s, newRole.value)
    ElMessage.success(`${s} 已加入（${r.note ?? ''}）`)
    newSymbol.value = ''
    await refresh()
  } catch (e) {
    ElMessage.error(msg(e))
  }
}

async function remove(row: InstrumentView) {
  try {
    await ElMessageBox.confirm(`把 ${row.symbol} 移出标的池？已有 K 线会保留。`, '移出', { type: 'warning' })
  } catch {
    return
  }
  try {
    await removeFromPool(row.symbol)
    await refresh()
  } catch (e) {
    ElMessage.error(msg(e))
  }
}

async function loadBars() {
  barsLoading.value = true
  try {
    bars.value = await getBars(barSymbol.value.trim().toUpperCase(), barFrom.value, barTo.value, barAdjust.value)
    if (bars.value.length === 0) ElMessage.info('该区间没有 K 线')
  } catch (e) {
    ElMessage.error(msg(e))
  } finally {
    barsLoading.value = false
  }
}

async function cancel() {
  await cancelJob()
  ElMessage.info('已请求取消，作业会在下一批边界停下')
}

function onVisibility() {
  if (document.visibilityState === 'visible') openStream()
  else closeStream()
}

onMounted(() => {
  refresh()
  getQuoteStatus().then((s) => (quoteStatus.value = s)).catch(() => {})
  openStream()
  document.addEventListener('visibilitychange', onVisibility)
  timer = setInterval(() => {
    if (document.visibilityState === 'visible') refresh()
  }, 5000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  document.removeEventListener('visibilitychange', onVisibility)
  closeStream()
})

function fmt(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('zh-CN', { timeZone: 'America/New_York', hour12: false }) + ' ET'
}
function statusTag(s: JobRun['status']): 'success' | 'warning' | 'danger' | 'info' {
  return s === 'OK' ? 'success' : s === 'PARTIAL' ? 'warning' : s === 'FAILED' ? 'danger' : 'info'
}
function sessionTag(s: Quote['session']): 'success' | 'warning' | 'info' {
  return s === 'RTH' ? 'success' : s === 'PRE' || s === 'AFTER' || s === 'OVERNIGHT' ? 'warning' : 'info'
}
function chgClass(v: number | null): string {
  return v == null ? '' : v > 0 ? 'up' : v < 0 ? 'down' : ''
}
function timeShort(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleTimeString('zh-CN', { timeZone: 'America/New_York', hour12: false })
}
function num(v: number | null | undefined, digits = 2): string {
  return v == null ? '—' : v.toLocaleString('en-US', { maximumFractionDigits: digits, minimumFractionDigits: digits })
}
</script>

<template>
  <div class="page">
    <div class="page__title">
      <h2>行情数据底座</h2>
      <span class="muted">每 5 秒自动刷新</span>
      <el-button size="small" @click="refresh">刷新</el-button>
    </div>
    <el-alert v-if="error" type="error" :title="'后端不可达：' + error" show-icon :closable="false" />

    <el-row v-if="coverage" :gutter="12">
      <el-col :span="6"><el-card shadow="never"><div class="stat"><div class="stat__v">{{ coverage.rows.toLocaleString() }}</div><div class="stat__l">日 K 行数（{{ coverage.instruments }} 只）</div><div class="stat__s">{{ coverage.earliest ?? '—' }} ～ {{ coverage.latest ?? '—' }}</div></div></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><div class="stat"><div class="stat__v">{{ coverage.universeCovered }} / {{ coverage.universeSize }}</div><div class="stat__l">全量标的已有 K 线</div><div class="stat__s">复权因子 {{ coverage.rehabCovered }}，富途不认识 {{ coverage.unresolved }}，错误 {{ coverage.withErrors }}</div></div></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><div class="stat"><div class="stat__v">{{ coverage.deepCovered }} / {{ coverage.poolSize + coverage.holdingSize }}</div><div class="stat__l">池 + 持仓已有 20 年深度</div><div class="stat__s">池 {{ coverage.poolSize }}，持仓 {{ coverage.holdingSize }}</div></div></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><div class="stat"><div class="stat__v">{{ coverage.quota.remain < 0 ? '—' : coverage.quota.remain + ' / ' + coverage.quota.total }}</div><div class="stat__l">历史 K 线额度剩余</div><div class="stat__s">{{ coverage.quota.detail }}</div></div></el-card></el-col>
    </el-row>

    <el-card shadow="never">
      <template #header>
        <div class="actions" style="margin: 0">
          <span>实时报价（不落库）</span>
          <el-tag size="small" :type="sseState === 'open' ? 'success' : 'warning'">SSE {{ sseState }}</el-tag>
          <span v-if="quoteStatus" class="muted">
            订阅 {{ quoteStatus.subscribed }} / 期望 {{ quoteStatus.desired }}{{ quoteStatus.paused ? '（已暂停）' : '' }}
            · 额度 {{ quoteStatus.quota ? quoteStatus.quota.usedQuota + '/' + (quoteStatus.quota.usedQuota + quoteStatus.quota.remainQuota) : '—' }}
            · 最近 1 分钟推送 {{ quoteStatus.pushesLastMinute }} · 最近推送 {{ timeShort(quoteStatus.lastPushAt) }}
            <span v-if="quoteStatus.lastError" class="down">· {{ quoteStatus.lastError }}</span>
          </span>
          <span style="flex: 1"></span>
          <el-button size="small" @click="quoteAction('订阅对账', reconcileQuotes)">订阅池与持仓</el-button>
          <el-button size="small" @click="quoteAction('暂停', pauseQuotes)">暂停</el-button>
          <el-button size="small" @click="quoteAction('恢复', resumeQuotes)">恢复</el-button>
        </div>
      </template>
      <el-table :data="quoteList()" size="small" max-height="360" empty-text="没有报价：先「订阅池与持仓」（需富途已连接）" @row-click="(row: Quote) => selectSymbol(row.instrument.symbol)">
        <el-table-column label="代码" width="90"><template #default="{ row }: { row: Quote }"><b>{{ row.instrument.symbol }}</b></template></el-table-column>
        <el-table-column label="时段" width="90"><template #default="{ row }: { row: Quote }"><el-tag size="small" :type="sessionTag(row.session)">{{ row.session }}</el-tag></template></el-table-column>
        <el-table-column label="有效价" width="100"><template #default="{ row }: { row: Quote }"><span :class="chgClass(row.change)">{{ num(row.price) }}</span></template></el-table-column>
        <el-table-column label="涨跌" width="90"><template #default="{ row }: { row: Quote }"><span :class="chgClass(row.change)">{{ num(row.change) }}</span></template></el-table-column>
        <el-table-column label="涨跌 %" width="90"><template #default="{ row }: { row: Quote }"><span :class="chgClass(row.changeRate)">{{ num(row.changeRate) }}</span></template></el-table-column>
        <el-table-column label="常规价" width="100"><template #default="{ row }: { row: Quote }">{{ num(row.rthPrice) }}</template></el-table-column>
        <el-table-column label="参考价" width="110"><template #default="{ row }: { row: Quote }"><span :title="`涨跌基准；盘前盘后为上一个常规时段收盘，券商昨收 ${num(row.lastClose)}`">{{ num(row.referenceClose ?? row.lastClose) }}</span></template></el-table-column>
        <el-table-column label="盘前" width="130"><template #default="{ row }: { row: Quote }"><span v-if="row.preMarket && row.preMarket.price">{{ num(row.preMarket.price) }} <span :class="chgClass(row.preMarket.changeRate)">({{ num(row.preMarket.changeRate) }}%)</span></span><span v-else>—</span></template></el-table-column>
        <el-table-column label="盘后" width="130"><template #default="{ row }: { row: Quote }"><span v-if="row.afterMarket && row.afterMarket.price">{{ num(row.afterMarket.price) }} <span :class="chgClass(row.afterMarket.changeRate)">({{ num(row.afterMarket.changeRate) }}%)</span></span><span v-else>—</span></template></el-table-column>
        <el-table-column label="成交量" width="120"><template #default="{ row }: { row: Quote }">{{ row.volume.toLocaleString() }}</template></el-table-column>
        <el-table-column label="收到" width="100"><template #default="{ row }: { row: Quote }">{{ timeShort(row.receivedAt) }}</template></el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" header="跑批">
      <div class="actions">
        <el-button size="small" @click="run('同步成分股', syncUniverse)">同步成分股</el-button>
        <el-button size="small" @click="confirmRun('全量轮转拉 K 线', '对全量标的按批订阅并拉取 1000 根日 K，约 8 分钟，不占历史额度。', () => refreshUniverse(1000))">全量拉 1000 根</el-button>
        <el-button size="small" @click="confirmRun('深度回补', '对池与持仓里还没有 20 年深度的标的依次回补，每只占 1 个历史额度。', backfillPending)">深度回补池/持仓</el-button>
        <el-button size="small" @click="run('每日增量', runIncrement)">每日增量</el-button>
        <el-button size="small" @click="confirmRun('全量复权因子', '对全量 518 只各调一次 requestRehab，约 5 分钟，不占历史额度。', () => refreshRehab(true))">全量复权因子</el-button>
        <el-button v-if="running" size="small" type="danger" plain @click="cancel">取消当前作业</el-button>
      </div>
      <el-alert v-if="running" type="info" :closable="false" show-icon class="running">
        <template #title>作业 #{{ running.id }} {{ running.job }} 运行中（{{ running.trigger }}，开始于 {{ fmt(running.startedAt) }}）：{{ running.progress || '…' }}</template>
      </el-alert>
      <el-table :data="jobs" size="small" empty-text="暂无作业记录">
        <el-table-column prop="id" label="#" width="60" />
        <el-table-column prop="job" label="作业" width="170" />
        <el-table-column prop="trigger" label="触发" width="90" />
        <el-table-column label="开始" width="190"><template #default="{ row }: { row: JobRun }">{{ fmt(row.startedAt) }}</template></el-table-column>
        <el-table-column label="结束" width="190"><template #default="{ row }: { row: JobRun }">{{ fmt(row.finishedAt) }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{ row }: { row: JobRun }"><el-tag :type="statusTag(row.status)" size="small">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="summary" label="摘要" min-width="320" show-overflow-tooltip />
      </el-table>
    </el-card>

    <el-card shadow="never" header="标的池（POOL 候选，上限 50；HOLDING 持仓；BENCHMARK 基准，只采集不选股）">
      <div class="actions">
        <el-input v-model="newSymbol" size="small" placeholder="代码，如 AAPL" style="width: 160px" @keyup.enter="add" />
        <el-select v-model="newRole" size="small" style="width: 120px">
          <el-option label="POOL" value="POOL" /><el-option label="HOLDING" value="HOLDING" /><el-option label="BENCHMARK" value="BENCHMARK" />
        </el-select>
        <el-button size="small" type="primary" @click="add">加入（自动深度回补）</el-button>
      </div>
      <el-table :data="pool" size="small" empty-text="标的池为空">
        <el-table-column prop="symbol" label="代码" width="90" />
        <el-table-column label="名称" min-width="160"><template #default="{ row }: { row: InstrumentView }">{{ row.nameCn ?? row.name ?? '—' }}</template></el-table-column>
        <el-table-column prop="role" label="角色" width="90" />
        <el-table-column label="指数" width="130"><template #default="{ row }: { row: InstrumentView }">{{ row.indexes.join(' ') || '—' }}</template></el-table-column>
        <el-table-column prop="depth" label="深度" width="90" />
        <el-table-column label="覆盖" width="220"><template #default="{ row }: { row: InstrumentView }">{{ row.earliest ?? '—' }} ～ {{ row.latest ?? '—' }}（{{ row.barCount }}）</template></el-table-column>
        <el-table-column prop="lastError" label="最近错误" min-width="200" show-overflow-tooltip />
        <el-table-column label="操作" width="90"><template #default="{ row }: { row: InstrumentView }"><el-button size="small" text type="danger" @click="remove(row)">移出</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" header="K 线查询">
      <div class="actions">
        <el-input v-model="barSymbol" size="small" placeholder="代码" style="width: 120px" @keyup.enter="loadBars" />
        <el-date-picker v-model="barFrom" size="small" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
        <el-date-picker v-model="barTo" size="small" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
        <el-select v-model="barAdjust" size="small" style="width: 130px">
          <el-option label="不复权" value="none" /><el-option label="前复权" value="forward" /><el-option label="后复权" value="backward" />
        </el-select>
        <el-button size="small" type="primary" :loading="barsLoading" @click="loadBars">查询</el-button>
        <span class="muted">{{ bars.length }} 根（点实时报价表的行可切换标的）</span>
      </div>
      <KlineChart v-if="bars.length" :bars="bars" :title="barSymbol.toUpperCase() + ' 日 K（' + (barAdjust === 'none' ? '不复权' : barAdjust === 'forward' ? '前复权' : '后复权') + '）'" />
      <el-table :data="bars" size="small" max-height="420" empty-text="—">
        <el-table-column prop="tradeDate" label="交易日" width="110" />
        <el-table-column label="开" width="100"><template #default="{ row }: { row: DailyBar }">{{ num(row.open) }}</template></el-table-column>
        <el-table-column label="高" width="100"><template #default="{ row }: { row: DailyBar }">{{ num(row.high) }}</template></el-table-column>
        <el-table-column label="低" width="100"><template #default="{ row }: { row: DailyBar }">{{ num(row.low) }}</template></el-table-column>
        <el-table-column label="收" width="100"><template #default="{ row }: { row: DailyBar }">{{ num(row.close) }}</template></el-table-column>
        <el-table-column label="涨跌 %" width="90"><template #default="{ row }: { row: DailyBar }">{{ num(row.changeRate) }}</template></el-table-column>
        <el-table-column label="成交量" width="130"><template #default="{ row }: { row: DailyBar }">{{ row.volume.toLocaleString() }}</template></el-table-column>
        <el-table-column label="成交额" width="150"><template #default="{ row }: { row: DailyBar }">{{ num(row.turnover, 0) }}</template></el-table-column>
        <el-table-column label="换手 %" width="90"><template #default="{ row }: { row: DailyBar }">{{ row.turnoverRate == null ? '—' : num(row.turnoverRate * 100, 3) }}</template></el-table-column>
        <el-table-column label="PE" width="80"><template #default="{ row }: { row: DailyBar }">{{ num(row.pe) }}</template></el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.page { display: flex; flex-direction: column; gap: 16px; }
.page__title { display: flex; align-items: center; gap: 12px; }
.page__title h2 { margin: 0; font-size: 18px; flex: 1; }
.muted { color: var(--el-text-color-secondary); font-size: 12px; }
.actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.running { margin-bottom: 10px; }
.stat__v { font-size: 22px; font-weight: 600; }
.stat__l { color: var(--el-text-color-regular); font-size: 13px; margin-top: 2px; }
.stat__s { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 4px; }
.up { color: #ef5350; }
.down { color: #26a69a; }
</style>
