<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAutoRefresh } from '../../composables/useAutoRefresh'
import { errMsg, num, timeEt } from '../../lib/format'
import {
  addToPool, getBars, getCoverage, getPool, removeFromPool, type Adjust, type CoverageView, type DailyBar, type InstrumentView,
} from '../../api/marketdata'
import { getQuoteStatus, pauseQuotes, quoteStreamUrl, reconcileQuotes, resumeQuotes, type Quote, type QuoteStatus } from '../../api/quotes'
import KlineChart from '../KlineChart.vue'

/**
 * 行情数据（原"行情数据底座"页，3.0.6 并进系统页）：覆盖统计、实时报价（SSE，只在本标签页打开时连）、标的池、K 线查询。
 * 跑批在"跑批"标签页。
 */
const props = defineProps<{ active: boolean }>()

const coverage = ref<CoverageView | null>(null)
const pool = ref<InstrumentView[]>([])
const error = ref<string | null>(null)

const newSymbol = ref('')
const newRole = ref<'POOL' | 'BENCHMARK'>('POOL')

const barSymbol = ref('AAPL')
const barFrom = ref(daysAgo(90))
const barTo = ref(today())
const barAdjust = ref<Adjust>('none')
const bars = ref<DailyBar[]>([])
const barsLoading = ref(false)

function today(): string {
  return new Date().toLocaleDateString('en-CA')
}
function daysAgo(n: number): string {
  return new Date(Date.now() - n * 86_400_000).toLocaleDateString('en-CA')
}

// ---- 实时报价（SSE）----
const quotes = ref<Map<string, Quote>>(new Map())
const quoteStatus = ref<QuoteStatus | null>(null)
const sseState = ref<'closed' | 'connecting' | 'open'>('closed')
let es: EventSource | null = null

/** computed 而不是模板里调的函数：后者每次推送都要把整张 Map 展开排序再全表重渲。 */
const quoteList = computed(() => [...quotes.value.values()].sort((a, b) => a.instrument.symbol.localeCompare(b.instrument.symbol)))

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
    ElMessage.error(label + '失败：' + errMsg(e))
  }
}

function selectSymbol(symbol: string) {
  barSymbol.value = symbol
  loadBars()
}

async function refresh() {
  if (!props.active) return
  try {
    const [c, p] = await Promise.all([getCoverage(), getPool()])
    coverage.value = c
    pool.value = p
    error.value = null
  } catch (e) {
    error.value = errMsg(e)
  }
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
    ElMessage.error(errMsg(e))
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
    ElMessage.error(errMsg(e))
  }
}

async function loadBars() {
  barsLoading.value = true
  try {
    bars.value = await getBars(barSymbol.value.trim().toUpperCase(), barFrom.value, barTo.value, barAdjust.value)
    if (bars.value.length === 0) ElMessage.info('该区间没有 K 线')
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    barsLoading.value = false
  }
}

/**
 * 本标签页打开时连推送、离开时断开；页面切到后台断开、回来再连（与原行情页一致）。
 * 挂载与切换标签时不看可见性：有的环境（如内嵌的浏览器面板）一直报 hidden，看了就永远连不上。
 */
function onVisibility() {
  if (document.visibilityState === 'visible' && props.active) openStream()
  else closeStream()
}
watch(() => props.active, (a) => {
  if (a) openStream()
  else closeStream()
  void refresh()
})

useAutoRefresh(refresh, 5000)

onMounted(() => {
  getQuoteStatus().then((s) => (quoteStatus.value = s)).catch(() => {})
  if (props.active) openStream()
  void refresh()
  document.addEventListener('visibilitychange', onVisibility)
})
onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibility)
  closeStream()
})

function sessionTag(s: Quote['session']): 'success' | 'warning' | 'info' {
  return s === 'RTH' ? 'success' : s === 'PRE' || s === 'AFTER' || s === 'OVERNIGHT' ? 'warning' : 'info'
}
function chgClass(v: number | null): string {
  return v == null ? '' : v > 0 ? 'up' : v < 0 ? 'down' : ''
}

const SESSION_LABEL: Record<string, string> = { RTH: '盘中', PRE: '盘前', AFTER: '盘后', OVERNIGHT: '夜盘', CLOSED: '休市' }
const ROLE_LABEL: Record<string, string> = { POOL: '池', HOLDING: '持仓', BENCHMARK: '基准' }
</script>

<template>
  <div class="tab">
    <el-alert v-if="error" type="error" :title="'后端不可达：' + error" show-icon :closable="false" />

    <section v-if="coverage" class="panel">
      <div class="panel__head"><h3>覆盖</h3><span class="panel__src">5 秒刷新</span></div>
      <div class="kv-grid">
        <div class="kv"><label>日 K 行数（{{ coverage.instruments }} 只）</label><b class="num">{{ coverage.rows.toLocaleString() }}</b></div>
        <div class="kv"><label>日 K 区间</label><b class="small">{{ coverage.earliest ?? '—' }} ～ {{ coverage.latest ?? '—' }}</b></div>
        <div class="kv"><label>全量标的已有 K 线</label><b class="num">{{ coverage.universeCovered }} / {{ coverage.universeSize }}</b></div>
        <div class="kv"><label>池 + 持仓 20 年深度</label><b class="num">{{ coverage.deepCovered }} / {{ coverage.poolSize + coverage.holdingSize }}</b></div>
        <div class="kv"><label>复权因子覆盖</label><b class="num">{{ coverage.rehabCovered }}</b></div>
        <div class="kv"><label>富途不认识 / 错误</label><b class="num">{{ coverage.unresolved }} / {{ coverage.withErrors }}</b></div>
        <div class="kv"><label>历史 K 线额度剩余</label><b class="num">{{ coverage.quota.remain < 0 ? '—' : coverage.quota.remain + ' / ' + coverage.quota.total }}</b></div>
      </div>
      <p class="panel__foot">{{ coverage.quota.detail }}</p>
    </section>

    <el-card shadow="never">
      <template #header>
        <div class="actions">
          <span>实时报价（不落库）</span>
          <el-tag size="small" :type="sseState === 'open' ? 'success' : 'warning'">推送{{ sseState === 'open' ? '已连接' : sseState === 'connecting' ? '连接中' : '未连接' }}</el-tag>
          <span v-if="quoteStatus" class="muted">
            订阅 {{ quoteStatus.subscribed }} / 期望 {{ quoteStatus.desired }}{{ quoteStatus.paused ? '（已暂停）' : '' }}
            · 额度 {{ quoteStatus.quota ? quoteStatus.quota.usedQuota + '/' + (quoteStatus.quota.usedQuota + quoteStatus.quota.remainQuota) : '—' }}
            · 最近 1 分钟推送 {{ quoteStatus.pushesLastMinute }} · 最近推送 {{ timeEt(quoteStatus.lastPushAt) }}
            <span v-if="quoteStatus.lastError" class="down">· {{ quoteStatus.lastError }}</span>
          </span>
          <span style="flex: 1"></span>
          <el-button size="small" @click="quoteAction('订阅对账', reconcileQuotes)">订阅池与持仓</el-button>
          <el-button size="small" @click="quoteAction('暂停', pauseQuotes)">暂停</el-button>
          <el-button size="small" @click="quoteAction('恢复', resumeQuotes)">恢复</el-button>
        </div>
      </template>
      <el-table :data="quoteList" size="small" max-height="360" empty-text="没有报价：先「订阅池与持仓」（需富途已连接）" @row-click="(row: Quote) => selectSymbol(row.instrument.symbol)">
        <el-table-column label="代码" width="90"><template #default="{ row }: { row: Quote }"><b>{{ row.instrument.symbol }}</b></template></el-table-column>
        <el-table-column label="时段" width="90"><template #default="{ row }: { row: Quote }"><el-tag size="small" :type="sessionTag(row.session)">{{ SESSION_LABEL[row.session] ?? row.session }}</el-tag></template></el-table-column>
        <el-table-column label="有效价" width="100"><template #default="{ row }: { row: Quote }"><span :class="chgClass(row.change)">{{ num(row.price) }}</span></template></el-table-column>
        <el-table-column label="涨跌" width="90"><template #default="{ row }: { row: Quote }"><span :class="chgClass(row.change)">{{ num(row.change) }}</span></template></el-table-column>
        <el-table-column label="涨跌 %" width="90"><template #default="{ row }: { row: Quote }"><span :class="chgClass(row.changeRate)">{{ num(row.changeRate) }}</span></template></el-table-column>
        <el-table-column label="常规价" width="100"><template #default="{ row }: { row: Quote }">{{ num(row.rthPrice) }}</template></el-table-column>
        <el-table-column label="参考价" width="110"><template #default="{ row }: { row: Quote }"><span :title="`涨跌基准；盘前盘后为上一个常规时段收盘，券商昨收 ${num(row.lastClose)}`">{{ num(row.referenceClose ?? row.lastClose) }}</span></template></el-table-column>
        <el-table-column label="盘前" width="130"><template #default="{ row }: { row: Quote }"><span v-if="row.preMarket && row.preMarket.price">{{ num(row.preMarket.price) }} <span :class="chgClass(row.preMarket.changeRate)">({{ num(row.preMarket.changeRate) }}%)</span></span><span v-else>—</span></template></el-table-column>
        <el-table-column label="盘后" width="130"><template #default="{ row }: { row: Quote }"><span v-if="row.afterMarket && row.afterMarket.price">{{ num(row.afterMarket.price) }} <span :class="chgClass(row.afterMarket.changeRate)">({{ num(row.afterMarket.changeRate) }}%)</span></span><span v-else>—</span></template></el-table-column>
        <el-table-column label="成交量" width="120"><template #default="{ row }: { row: Quote }">{{ row.volume.toLocaleString() }}</template></el-table-column>
        <el-table-column label="收到" min-width="100"><template #default="{ row }: { row: Quote }">{{ timeEt(row.receivedAt) }}</template></el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" header="标的池（池：候选，上限 50；持仓：由盈透持仓自动维护；基准：只采集不选股）">
      <div class="actions">
        <el-input v-model="newSymbol" size="small" placeholder="代码，如 AAPL" style="width: 160px" @keyup.enter="add" />
        <el-select v-model="newRole" size="small" style="width: 200px">
          <el-option label="池（POOL）" value="POOL" /><el-option label="持仓（盈透自动维护）" value="HOLDING" disabled /><el-option label="基准（BENCHMARK）" value="BENCHMARK" />
        </el-select>
        <el-button size="small" type="primary" @click="add">加入（自动深度回补）</el-button>
      </div>
      <el-table :data="pool" size="small" empty-text="标的池为空">
        <el-table-column prop="symbol" label="代码" width="90" />
        <el-table-column label="名称" min-width="160"><template #default="{ row }: { row: InstrumentView }">{{ row.nameCn ?? row.name ?? '—' }}</template></el-table-column>
        <el-table-column label="角色" width="80"><template #default="{ row }: { row: InstrumentView }">{{ row.role ? ROLE_LABEL[row.role] ?? row.role : '—' }}</template></el-table-column>
        <el-table-column label="指数" width="130"><template #default="{ row }: { row: InstrumentView }">{{ row.indexes.join(' ') || '—' }}</template></el-table-column>
        <el-table-column prop="depth" label="深度" width="90" />
        <el-table-column label="覆盖" width="260"><template #default="{ row }: { row: InstrumentView }">{{ row.earliest ?? '—' }} ～ {{ row.latest ?? '—' }}（{{ row.barCount }}）</template></el-table-column>
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
      <KlineChart v-if="bars.length" :bars="bars" :ma="[20, 50, 200]" :title="barSymbol.toUpperCase() + ' 日 K（' + (barAdjust === 'none' ? '不复权' : barAdjust === 'forward' ? '前复权' : '后复权') + '）'" />
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
        <el-table-column label="PE" min-width="80"><template #default="{ row }: { row: DailyBar }">{{ num(row.pe) }}</template></el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.tab { display: flex; flex-direction: column; gap: 12px; }
.actions { margin-bottom: 10px; }
.kv b.small { font-size: 12px; font-weight: 500; }
</style>
