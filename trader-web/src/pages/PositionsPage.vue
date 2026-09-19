<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import PositionDrawer, { type PositionRow } from '../components/positions/PositionDrawer.vue'
import { errMsg, money, num, signed, timeEt, trend } from '../lib/format'
import { sectorCn } from '../lib/sector'
import { accountApi, type LiveView, type SnapshotView } from '../api/account'
import { getPool, type InstrumentView } from '../api/marketdata'

/**
 * 持仓页：整宽持仓列表，点一行从右侧滑出详情抽屉（头部数据、日 K 加成本线、基本面摘要）。
 *
 * 数据口径与仪表盘一致：优先盈透实时（/api/account/live），全部是盈透原值，不做折算；拿不到时退回最近一份收盘快照。
 * 选中的标的放在 ?symbol= 里，刷新与分享都能回到同一只（不用 /positions/BRK.B：前端路由只用单段、不带点）。
 *
 * 刷新：实时账户 5 秒一轮（预热中 1.5 秒）；池与快照 5 分钟一轮；页面切到后台即暂停。
 */
const route = useRoute()
const router = useRouter()

const live = ref<LiveView | null>(null)
const view = ref<SnapshotView | null>(null)
const pool = ref<InstrumentView[]>([])
const loaded = ref(false)
const error = ref<string | null>(null)

/** 盈透的类别股带空格（BRK B），富途与库里是点（BRK.B）。 */
const norm = (s: string) => s.replace(' ', '.')

const isLive = computed(() => {
  const l = live.value
  return !!l && (l.status === 'LIVE' || l.status === 'DISCONNECTED') && l.positionsUpdatedAt !== null
})
const source = computed<'LIVE' | 'SNAPSHOT'>(() => (isLive.value ? 'LIVE' : 'SNAPSHOT'))

const instruments = computed(() => new Map(pool.value.map((p) => [p.symbol, p])))

/** 市值降序，现金工具排最后（它占比大，但不是要逐只看的持仓）。 */
const rows = computed<PositionRow[]>(() => {
  const list: PositionRow[] = isLive.value
    ? live.value!.positions.map((p) => ({
        symbol: p.symbol,
        quantity: p.quantity,
        averageCost: p.averageCost,
        costBasis: p.costBasis,
        price: p.last,
        change: p.change,
        changePct: p.changePct,
        priceAt: p.lastAt,
        priceDelayed: p.lastDelayed,
        marketValue: p.marketValue,
        dailyPnl: p.dailyPnl,
        unrealizedPnl: p.unrealizedPnl,
        portfolioPct: p.portfolioPct,
        cashEquivalent: p.cashEquivalent,
      }))
    : (view.value?.positions ?? []).map((p) => ({
        symbol: norm(p.symbol),
        quantity: p.quantity,
        averageCost: p.averageCost,
        costBasis: null,
        price: p.price,
        change: null,
        changePct: null,
        priceAt: null,
        priceDelayed: false,
        marketValue: p.marketValue,
        dailyPnl: null,
        unrealizedPnl: p.unrealizedPnl,
        portfolioPct: null,
        cashEquivalent: p.cashEquivalent,
      }))
  return list.sort((a, b) => Number(a.cashEquivalent) - Number(b.cashEquivalent) || (b.marketValue ?? 0) - (a.marketValue ?? 0))
})

const stockCount = computed(() => rows.value.filter((r) => !r.cashEquivalent).length)
const cashCount = computed(() => rows.value.length - stockCount.value)

// ---- 抽屉：选中状态在 URL 里 ----
const picked = computed(() => (typeof route.query.symbol === 'string' ? route.query.symbol : null))
const pickedRow = computed(() => rows.value.find((r) => r.symbol === picked.value) ?? null)
const drawer = computed({
  get: () => pickedRow.value !== null,
  set: (open: boolean) => { if (!open) void router.replace({ query: {} }) },
})
function open(symbol: string) {
  void router.replace({ query: { symbol } })
}

function nameOf(symbol: string): string | null {
  const i = instruments.value.get(symbol)
  return i?.nameCn ?? i?.name ?? null
}
function signedNum(v: number | null): string {
  return v === null ? '—' : (v > 0 ? '+' : '') + num(v)
}

// ---- 取数 ----
async function loadDaily() {
  error.value = null
  try {
    const [p, latest] = await Promise.all([
      getPool(),
      accountApi.latest().catch((e) => {
        if ((e as { response?: { status?: number } }).response?.status === 404) return null // 还没拍过快照
        throw e
      }),
    ])
    pool.value = p
    view.value = latest
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    loaded.value = true
  }
}

async function loadLive() {
  try {
    live.value = await accountApi.live()
  } catch {
    live.value = null // 接口不可达：退回收盘快照
  }
}

let dailyTimer = 0
let liveTimer = 0
function scheduleLive() {
  window.clearTimeout(liveTimer)
  liveTimer = window.setTimeout(async () => {
    await loadLive()
    scheduleLive()
  }, live.value?.status === 'WARMING' ? 1_500 : 5_000)
}
function start() {
  void loadDaily()
  void loadLive().then(scheduleLive)
  dailyTimer = window.setInterval(loadDaily, 5 * 60_000)
}
function stop() {
  window.clearInterval(dailyTimer)
  window.clearTimeout(liveTimer)
  dailyTimer = liveTimer = 0
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
    <PageHeader title="持仓" hint="盈透持仓，全部为盈透原值（与盈透 App 一致）；点一行看日 K、成本线与基本面" />
    <el-alert v-if="error" :title="'取数失败：' + error" type="error" show-icon :closable="false" />

    <section class="panel">
      <div class="summary">
        <div class="summary__item">
          <label>持仓</label>
          <b class="num">{{ stockCount }} 只</b>
          <span v-if="cashCount" class="muted">+ 现金工具 {{ cashCount }}</span>
        </div>
        <template v-if="source === 'LIVE'">
          <div class="summary__item">
            <label>股票市值</label>
            <b class="num">{{ money(live?.money?.stockMarketValue) }}</b>
          </div>
          <div class="summary__item">
            <label>当日盈亏</label>
            <b v-if="live?.pnl" class="num" :class="trend(live.pnl.daily)">{{ signed(live.pnl.daily) }}</b>
            <span v-else class="muted">等待盈透推送…</span>
          </div>
          <div class="summary__item">
            <label>浮动盈亏</label>
            <b v-if="live?.pnl" class="num" :class="trend(live.pnl.unrealized)">{{ signed(live.pnl.unrealized) }}</b>
            <span v-else class="muted">等待盈透推送…</span>
          </div>
          <span class="panel__grow" />
          <span class="panel__src">盈透实时 · 持仓 {{ timeEt(live?.positionsUpdatedAt) }} ET · 盈亏 {{ timeEt(live?.pnl?.updatedAt) }} ET</span>
        </template>
        <template v-else>
          <div class="summary__item">
            <label>股票市值</label>
            <b class="num">{{ money(view?.snapshot.stockMarketValue) }}</b>
          </div>
          <span class="panel__grow" />
          <span class="panel__src">
            盈透实时暂不可用<template v-if="live?.status === 'WARMING'">（正在订阅…）</template>，显示
            {{ view ? view.snapshot.asOfDate + ' 收盘快照' : '—（还没有快照）' }}
          </span>
        </template>
      </div>
    </section>

    <section class="panel">
      <div class="dtable-wrap">
        <table class="dtable">
          <thead>
            <tr>
              <th>代码</th><th>行业</th><th class="r">数量</th><th class="r">成本价</th>
              <th v-if="source === 'LIVE'" class="r">成本</th>
              <th class="r">{{ source === 'LIVE' ? '最新价' : '收盘价' }}</th>
              <template v-if="source === 'LIVE'"><th class="r">涨跌</th><th class="r">涨跌 %</th></template>
              <th class="r">市值</th>
              <th v-if="source === 'LIVE'" class="r">当日盈亏</th>
              <th class="r">浮动盈亏</th>
              <th v-if="source === 'LIVE'" class="r">占组合</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.symbol" class="clickable" :class="{ picked: r.symbol === picked }" @click="open(r.symbol)">
              <td>
                <b>{{ r.symbol }}</b>
                <span v-if="nameOf(r.symbol)" class="name">{{ nameOf(r.symbol) }}</span>
                <el-tag v-if="r.cashEquivalent" size="small" type="info" class="tag">现金工具</el-tag>
              </td>
              <td class="muted-cell" :title="instruments.get(r.symbol)?.subIndustry ?? ''">{{ sectorCn(instruments.get(r.symbol)?.sector) ?? '—' }}</td>
              <td class="r num">{{ r.quantity.toLocaleString(undefined, { maximumFractionDigits: 4 }) }}</td>
              <td class="r num">{{ money(r.averageCost) }}</td>
              <td v-if="source === 'LIVE'" class="r num">{{ money(r.costBasis) }}</td>
              <td class="r num">
                {{ num(r.price) }}<span v-if="r.priceDelayed" class="stamp">延迟</span>
              </td>
              <template v-if="source === 'LIVE'">
                <td class="r num" :class="trend(r.change)">{{ signedNum(r.change) }}</td>
                <td class="r num" :class="trend(r.changePct)">{{ r.changePct === null ? '—' : signedNum(r.changePct) + '%' }}</td>
              </template>
              <td class="r num">{{ money(r.marketValue) }}</td>
              <td v-if="source === 'LIVE'" class="r num" :class="trend(r.dailyPnl)">{{ signed(r.dailyPnl) }}</td>
              <td class="r num" :class="trend(r.unrealizedPnl)">{{ signed(r.unrealizedPnl) }}</td>
              <td v-if="source === 'LIVE'" class="r num">{{ r.portfolioPct === null ? '—' : num(r.portfolioPct) + '%' }}</td>
            </tr>
            <tr v-if="!rows.length">
              <td :colspan="source === 'LIVE' ? 12 : 7" class="empty">{{ loaded ? '无持仓' : '加载中…' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-if="source === 'LIVE'" class="panel__foot">
        最新价与前收来自盈透行情，市值、当日盈亏、浮动盈亏来自盈透逐只盈亏；涨跌 = 最新价 − 前收，成本 = 成本价 × 数量，占组合 = 市值 ÷ 净值，与盈透 App 同口径。
        按市值从大到小，现金工具排最后。
      </p>
      <p v-else class="panel__foot">收盘价与市值是快照当晚按收盘价的估值。</p>
    </section>

    <PositionDrawer v-model="drawer" :row="pickedRow" :instrument="pickedRow ? instruments.get(pickedRow.symbol) ?? null : null" :source="source" />
  </div>
</template>

<style scoped>
.summary { display: flex; align-items: baseline; gap: 28px; flex-wrap: wrap; }
.summary__item { display: flex; align-items: baseline; gap: 8px; }
.summary__item label { font-size: 12px; color: var(--el-text-color-secondary); }
.summary__item b { font-size: 16px; font-weight: 600; }
.dtable { font-size: 13px; }
.name { margin-left: 8px; font-size: 12px; color: var(--el-text-color-secondary); }
.tag { margin-left: 6px; }
.muted-cell { color: var(--el-text-color-secondary); }
.stamp {
  display: inline-block; margin-left: 3px; font-size: 10px; line-height: 14px; padding: 0 3px; border-radius: 3px;
  color: var(--el-color-warning); background: var(--el-color-warning-light-9);
}
tr.picked td { background: var(--el-color-primary-light-9); }
</style>
