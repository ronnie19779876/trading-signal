<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '../components/PageHeader.vue'
import ValuationScreen from '../components/fundamentals/ValuationScreen.vue'
import PeChart from '../components/fundamentals/PeChart.vue'
import ReportsPanel from '../components/fundamentals/ReportsPanel.vue'
import SotpPanel from '../components/fundamentals/SotpPanel.vue'
import { useAutoRefresh } from '../composables/useAutoRefresh'
import { big, errMsg, negative, numMax } from '../lib/format'
import { sectorCn } from '../lib/sector'
import { fundamentalsApi, type FundamentalsCoverage, type FundamentalsOverview, type ValuationSnapshot } from '../api/fundamentals'
import { getJobs, getUniverse, type InstrumentView, type RunningJob } from '../api/marketdata'

/**
 * 基本面页：上面全市场估值筛选表，下面单只详情（估值、市盈率走势、财报）；维护操作收在页底。
 * 选中的标的放在 ?symbol=（持仓抽屉的"基本面页 →"也是这样跳过来的）。全部是券商原值，分位与中位数是本系统算的，图上写明。
 */
const route = useRoute()
const router = useRouter()

const universe = ref<InstrumentView[]>([])
const dayDate = ref<string | null>(null)
const dayRows = ref<ValuationSnapshot[]>([])
const overview = ref<FundamentalsOverview | null>(null)
const loaded = ref(false)
const loadingOverview = ref(false)
const error = ref<string | null>(null)

const symbol = computed(() => (typeof route.query.symbol === 'string' && route.query.symbol ? route.query.symbol.toUpperCase() : 'NVDA'))
const instrument = computed(() => universe.value.find((u) => u.symbol === symbol.value) ?? null)
const holdings = computed(() => universe.value.filter((u) => u.role === 'HOLDING'))
const poolOnly = computed(() => universe.value.filter((u) => u.role === 'POOL'))
const ROLE_LABEL: Record<string, string> = { HOLDING: '持仓', POOL: '池', BENCHMARK: '基准' }

async function loadAll() {
  error.value = null
  try {
    const [u, d] = await Promise.all([getUniverse(), fundamentalsApi.valuationsOn()])
    universe.value = u
    dayDate.value = d.date
    dayRows.value = d.rows
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    loaded.value = true
  }
}

async function loadOverview(s: string) {
  loadingOverview.value = true
  try {
    overview.value = await fundamentalsApi.overview(s)
  } catch (e) {
    overview.value = null
    const status = (e as { response?: { status?: number } }).response?.status
    if (status !== 404) error.value = errMsg(e)
  } finally {
    loadingOverview.value = false
  }
}
watch(symbol, (s) => void loadOverview(s), { immediate: true })

const detail = ref<HTMLElement | null>(null)
async function pick(s: string, scroll = true) {
  if (!s) return
  await router.replace({ query: { ...route.query, symbol: s } })
  if (scroll) {
    await nextTick()
    detail.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

const v = computed(() => overview.value?.valuation ?? null)
const displayName = computed(() => instrument.value?.nameCn ?? instrument.value?.name ?? overview.value?.name ?? null)

// ---- 维护：覆盖统计与刷新作业 ----
const coverage = ref<FundamentalsCoverage | null>(null)
const running = ref<RunningJob | null>(null)
const maintenance = ref<string[]>([])
async function refreshStatus() {
  try {
    coverage.value = await fundamentalsApi.coverage()
    const r = (await getJobs(1)).running
    running.value = r && 'id' in r ? (r as RunningJob) : null
  } catch {
    // 状态轮询失败不打扰用户，下一轮会再试
  }
}
useAutoRefresh(refreshStatus, 5000)

async function runValuation() {
  try {
    const { jobId } = await fundamentalsApi.refreshValuation()
    ElMessage.success(`估值快照作业 #${jobId} 已提交`)
    await refreshStatus()
  } catch (e) {
    ElMessage.error(errMsg(e))
  }
}
async function runFinancials(all: boolean) {
  try {
    const { jobId } = await fundamentalsApi.refreshFinancials(all)
    ElMessage.success(`财报作业 #${jobId} 已提交${all ? '（全量约 41 分钟）' : ''}`)
    await refreshStatus()
  } catch (e) {
    ElMessage.error(errMsg(e))
  }
}

void loadAll()
</script>

<template>
  <div class="page">
    <PageHeader title="基本面" hint="估值每个交易日收盘后全量刷新；财报池与持仓每周刷新。全部是券商原值" />
    <el-alert v-if="error" :title="'取数失败：' + error" type="error" show-icon :closable="false" />
    <el-alert v-if="running" :title="`作业 #${running.id} ${running.job} 运行中：${running.progress}`" type="info" show-icon :closable="false" />

    <ValuationScreen :date="dayDate" :valuations="dayRows" :universe="universe" :selected="symbol" :loaded="loaded" @select="pick" />

    <!-- 单只详情 -->
    <div ref="detail" class="detail">
      <section v-loading="loadingOverview" class="panel">
        <div class="head">
          <h2 class="sym">{{ symbol }}</h2>
          <span v-if="displayName" class="nm">{{ displayName }}</span>
          <template v-if="instrument">
            <el-tag v-if="instrument.role" size="small" effect="plain">{{ ROLE_LABEL[instrument.role] }}</el-tag>
            <el-tooltip v-if="instrument.sector" :content="instrument.subIndustry ?? instrument.sector" :disabled="!instrument.subIndustry">
              <span class="muted">{{ sectorCn(instrument.sector) }}</span>
            </el-tooltip>
            <span v-if="instrument.indexes.length" class="muted">{{ instrument.indexes.join(' / ') }}</span>
          </template>
          <span class="grow" />
          <el-select :model-value="symbol" filterable size="small" placeholder="搜代码或名称" style="width: 240px"
                     @change="(s: string) => pick(s, false)">
            <el-option v-for="u in universe" :key="u.symbol" :value="u.symbol" :label="`${u.symbol} ${u.nameCn ?? u.name ?? ''}`" />
          </el-select>
        </div>
        <div v-if="holdings.length || poolOnly.length" class="chips">
          <span class="muted">持仓</span>
          <el-check-tag v-for="u in holdings" :key="u.symbol" :checked="u.symbol === symbol" @change="pick(u.symbol, false)">{{ u.symbol }}</el-check-tag>
          <span class="muted sep">池</span>
          <el-check-tag v-for="u in poolOnly" :key="u.symbol" :checked="u.symbol === symbol" @change="pick(u.symbol, false)">{{ u.symbol }}</el-check-tag>
        </div>

        <template v-if="v">
          <div class="kv-grid">
            <div class="kv"><label>总市值</label><b class="num">{{ big(v.marketCap) }}</b></div>
            <div class="kv"><label>流通市值</label><b class="num">{{ big(v.floatMarketCap) }}</b></div>
            <div class="kv"><label>市盈率</label><b class="num" :class="negative(v.pe)">{{ numMax(v.pe) }}</b></div>
            <div class="kv"><label>市盈率 TTM</label><b class="num" :class="negative(v.peTtm)">{{ numMax(v.peTtm) }}</b></div>
            <div class="kv"><label>市净率</label><b class="num" :class="negative(v.pb)">{{ numMax(v.pb) }}</b></div>
            <div class="kv"><label>每股收益</label><b class="num">{{ numMax(v.eps) }}</b></div>
            <div class="kv"><label>每股净资产</label><b class="num">{{ numMax(v.netAssetPerShare) }}</b></div>
            <div class="kv"><label>净利润</label><b class="num" :class="negative(v.netProfit)">{{ big(v.netProfit) }}</b></div>
            <div class="kv"><label>股息率 TTM</label><b class="num">{{ v.dividendYieldTtm === null ? '—' : numMax(v.dividendYieldTtm) + '%' }}</b></div>
            <div class="kv"><label>换手率</label><b class="num">{{ v.turnoverRate === null ? '—' : numMax(v.turnoverRate) + '%' }}</b></div>
            <div v-if="v.navPerShare !== null" class="kv"><label>净值</label><b class="num">{{ numMax(v.navPerShare) }}</b></div>
            <div v-if="v.premium !== null" class="kv"><label>溢价</label><b class="num" :class="negative(v.premium)">{{ numMax(v.premium) }}%</b></div>
          </div>
          <p class="panel__foot">估值取自 {{ v.asOf.slice(0, 10) }}。市盈率、市净率为负是亏损或资不抵债的真实数据；基金没有市盈率，个股没有净值。</p>
        </template>
        <el-empty v-else-if="!loadingOverview" :image-size="60" description="还没有这只标的的估值" />
      </section>

      <PeChart :symbol="symbol" />
      <ReportsPanel :symbol="symbol" />
      <SotpPanel :symbol="symbol" />
    </div>

    <!-- 维护 -->
    <el-collapse v-model="maintenance" class="panel maintenance">
      <el-collapse-item name="m">
        <template #title>
          <span class="m-title">维护</span>
          <span class="panel__src m-src">覆盖统计 · 刷新估值 · 刷新财报</span>
          <span v-if="coverage" class="panel__src m-src">当日估值 {{ coverage.withValuationOnDate }} / {{ coverage.targets }} · 池与持仓有财报 {{ coverage.poolWithReports }} / {{ coverage.poolSize }}</span>
        </template>
        <div v-if="coverage" class="kv-grid">
          <div class="kv"><label>当日有估值（{{ coverage.latestDate ?? '—' }}）</label><b class="num">{{ coverage.withValuationOnDate }} / {{ coverage.targets }}</b></div>
          <div class="kv"><label>财报期数（四类合计）</label><b class="num">{{ coverage.reports.toLocaleString() }}</b></div>
          <div class="kv"><label>池与持仓有财报（基金没有）</label><b class="num">{{ coverage.poolWithReports }} / {{ coverage.poolSize }}</b></div>
        </div>
        <div class="m-actions">
          <el-button size="small" :disabled="!!running" @click="runValuation">刷新估值</el-button>
          <el-button size="small" :disabled="!!running" @click="runFinancials(false)">刷新财报（池与持仓）</el-button>
          <el-button size="small" :disabled="!!running" @click="runFinancials(true)">全量回补财报（约 41 分钟）</el-button>
          <span class="muted">估值只能在收盘窗口内刷新（交易日美东 16:15 至次日 04:00）；每天自动跑，一般不用手动</span>
        </div>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<style scoped>
.detail { display: flex; flex-direction: column; gap: var(--tr-page-gap); scroll-margin-top: 70px; }
.head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; margin-bottom: 8px; }
.sym { margin: 0; font-size: 20px; }
.nm { font-size: 14px; color: var(--el-text-color-regular); }
.grow { flex: 1; }
.chips { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-bottom: 10px; }
.chips .sep { margin-left: 10px; }
.chips :deep(.el-check-tag) { padding: 2px 8px; font-size: 12px; }
.maintenance { padding-top: 0; padding-bottom: 0; }
.maintenance :deep(.el-collapse-item__header) { border-bottom: 0; }
.maintenance :deep(.el-collapse-item__wrap) { border-bottom: 0; }
.m-title { font-size: 14px; font-weight: 600; color: var(--el-text-color-primary); }
.m-src { margin-left: 10px; }
.m-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 10px; }
</style>
