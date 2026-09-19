<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '../components/PageHeader.vue'
import StatCard from '../components/StatCard.vue'
import { useAutoRefresh } from '../composables/useAutoRefresh'
import { big, errMsg, negative, numMax } from '../lib/format'
import {
  fundamentalsApi,
  type FinancialReport,
  type FundamentalsCoverage,
  type FundamentalsOverview,
  type StatementKind,
  type ValuationSnapshot,
} from '../api/fundamentals'
import { getJobs, type RunningJob } from '../api/marketdata'

const coverage = ref<FundamentalsCoverage | null>(null)
const running = ref<RunningJob | null>(null)
const error = ref<string | null>(null)

// 从持仓页等处跳过来时带 ?symbol=
const route = useRoute()
const symbol = ref(typeof route.query.symbol === 'string' && route.query.symbol ? route.query.symbol : 'NVDA')
const overview = ref<FundamentalsOverview | null>(null)
const valuations = ref<ValuationSnapshot[]>([])
const reports = ref<FinancialReport[]>([])
const statement = ref<StatementKind>('main_index')
const loading = ref(false)

const STATEMENTS: { value: StatementKind; label: string }[] = [
  { value: 'main_index', label: '主要指标' },
  { value: 'income', label: '利润表' },
  { value: 'balance_sheet', label: '资产负债表' },
  { value: 'cash_flow', label: '现金流量表' },
]

/** 财报按字段编号横向铺开：一行一个字段，一列一期。券商字段随行业不同，所以列由数据决定而不是写死。 */
const reportGrid = computed(() => {
  const periods = reports.value
  const names = new Map<number, string>()
  for (const r of periods) {
    for (const i of r.items) if (i.name) names.set(i.fieldId, i.name)
  }
  const fieldIds = [...names.keys()].sort((a, b) => a - b)
  return fieldIds.map((id) => ({
    fieldId: id,
    name: names.get(id) ?? String(id),
    values: periods.map((r) => r.items.find((i) => i.fieldId === id) ?? null),
  }))
})

async function load() {
  const s = symbol.value.trim().toUpperCase()
  if (!s) return
  loading.value = true
  error.value = null
  try {
    // 三个请求互不依赖，串着 await 就是三个来回。
    const [o, v, r] = await Promise.all([
      fundamentalsApi.overview(s),
      fundamentalsApi.valuation(s),
      fundamentalsApi.reports(s, statement.value, 8),
    ])
    overview.value = o
    valuations.value = v
    reports.value = r
  } catch (e) {
    error.value = errMsg(e)
    overview.value = null
    valuations.value = []
    reports.value = []
  } finally {
    loading.value = false
  }
}

async function loadReports() {
  const s = symbol.value.trim().toUpperCase()
  try {
    reports.value = await fundamentalsApi.reports(s, statement.value, 8)
  } catch (e) {
    error.value = errMsg(e)
  }
}

async function refreshStatus() {
  try {
    coverage.value = await fundamentalsApi.coverage()
    const r = (await getJobs(1)).running
    running.value = r && 'id' in r ? (r as RunningJob) : null
  } catch {
    // 状态轮询失败不打扰用户，下一轮会再试
  }
}

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

useAutoRefresh(refreshStatus, 5000)
load()
</script>

<template>
  <div class="page">
    <PageHeader title="基本面" hint="估值快照每交易日全量刷新；财报只对池与持仓每周刷新，可手动全量回补" />

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />

    <el-row v-if="coverage" :gutter="12">
      <el-col :xs="24" :sm="12" :lg="6">
        <StatCard :value="`${coverage.withValuationOnDate} / ${coverage.targets}`" label="当日有估值快照"
                  :sub="`最新 ${coverage.latestDate ?? '—'}`" />
      </el-col>
      <el-col :xs="24" :sm="12" :lg="6">
        <StatCard :value="coverage.reports.toLocaleString()" label="财报期数" sub="四类报表合计" />
      </el-col>
      <el-col :xs="24" :sm="12" :lg="6">
        <StatCard :value="`${coverage.poolWithReports} / ${coverage.poolSize}`" label="池与持仓有财报" sub="基金没有财报，属正常" />
      </el-col>
      <el-col :xs="24" :sm="12" :lg="6">
        <el-card shadow="never" class="ops">
          <div class="actions">
            <el-button size="small" :disabled="!!running" @click="runValuation">刷新估值</el-button>
            <el-button size="small" :disabled="!!running" @click="runFinancials(false)">刷新财报（池）</el-button>
          </div>
          <el-button size="small" text :disabled="!!running" @click="runFinancials(true)">全量回补财报（约 41 分钟）</el-button>
        </el-card>
      </el-col>
    </el-row>

    <el-alert
      v-if="running"
      :title="`作业 #${running.id} ${running.job} 运行中：${running.progress}`"
      type="info"
      show-icon
      :closable="false"
    />

    <el-card shadow="never">
      <div class="actions query">
        <el-input v-model="symbol" size="small" placeholder="代码，如 NVDA" style="width: 160px" @keyup.enter="load" />
        <el-button type="primary" size="small" :loading="loading" @click="load">查询</el-button>
        <span v-if="overview?.name" class="muted">{{ overview.name }}</span>
      </div>

      <template v-if="overview?.valuation">
        <el-descriptions :column="4" size="small" border>
          <el-descriptions-item label="总市值">{{ big(overview.valuation.marketCap) }}</el-descriptions-item>
          <el-descriptions-item label="流通市值">{{ big(overview.valuation.floatMarketCap) }}</el-descriptions-item>
          <el-descriptions-item label="市盈率">
            <span :class="negative(overview.valuation.pe)">{{ numMax(overview.valuation.pe) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="市盈率 TTM">
            <span :class="negative(overview.valuation.peTtm)">{{ numMax(overview.valuation.peTtm) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="市净率">
            <span :class="negative(overview.valuation.pb)">{{ numMax(overview.valuation.pb) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="每股收益">{{ numMax(overview.valuation.eps) }}</el-descriptions-item>
          <el-descriptions-item label="每股净资产">{{ numMax(overview.valuation.netAssetPerShare) }}</el-descriptions-item>
          <el-descriptions-item label="换手率">{{ numMax(overview.valuation.turnoverRate) }}%</el-descriptions-item>
          <el-descriptions-item label="净资产">{{ big(overview.valuation.netAsset) }}</el-descriptions-item>
          <el-descriptions-item label="净利润">
            <span :class="negative(overview.valuation.netProfit)">{{ big(overview.valuation.netProfit) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="股息 TTM">{{ numMax(overview.valuation.dividendTtm) }}</el-descriptions-item>
          <el-descriptions-item label="股息率 TTM">{{ numMax(overview.valuation.dividendYieldTtm) }}%</el-descriptions-item>
          <el-descriptions-item v-if="overview.valuation.navPerShare !== null" label="净值">
            {{ numMax(overview.valuation.navPerShare) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="overview.valuation.premium !== null" label="溢价">
            <span :class="negative(overview.valuation.premium)">{{ numMax(overview.valuation.premium) }}%</span>
          </el-descriptions-item>
        </el-descriptions>
        <div class="muted hint">
          市盈率市净率为负是亏损或资不抵债的真实数据。基金没有市盈率，个股没有净值。
          估值取自 {{ overview.valuation.asOf }}。
        </div>
      </template>
      <el-empty v-else-if="!loading" description="还没有这只标的的估值，先跑一次「刷新估值」" :image-size="60" />
    </el-card>

    <el-card v-if="valuations.length" shadow="never" header="估值序列（最近 90 天）">
      <el-table :data="[...valuations].reverse()" size="small" max-height="300">
        <el-table-column label="日期" width="110">
          <template #default="{ row }: { row: ValuationSnapshot }">{{ row.asOf.slice(0, 10) }}</template>
        </el-table-column>
        <el-table-column label="总市值" width="130">
          <template #default="{ row }: { row: ValuationSnapshot }">{{ big(row.marketCap) }}</template>
        </el-table-column>
        <el-table-column label="市盈率" width="110">
          <template #default="{ row }: { row: ValuationSnapshot }">
            <span :class="negative(row.pe)">{{ numMax(row.pe) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="市盈率 TTM" width="120">
          <template #default="{ row }: { row: ValuationSnapshot }">
            <span :class="negative(row.peTtm)">{{ numMax(row.peTtm) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="市净率" width="110">
          <template #default="{ row }: { row: ValuationSnapshot }">
            <span :class="negative(row.pb)">{{ numMax(row.pb) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="每股收益" width="110">
          <template #default="{ row }: { row: ValuationSnapshot }">{{ numMax(row.eps) }}</template>
        </el-table-column>
        <el-table-column label="换手率" width="100">
          <template #default="{ row }: { row: ValuationSnapshot }">{{ numMax(row.turnoverRate) }}%</template>
        </el-table-column>
        <el-table-column label="停牌" min-width="80">
          <template #default="{ row }: { row: ValuationSnapshot }">{{ row.suspended ? '是' : '—' }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="actions">
          <span>财务报表</span>
          <el-radio-group v-model="statement" size="small" @change="loadReports">
            <el-radio-button v-for="s in STATEMENTS" :key="s.value" :value="s.value">{{ s.label }}</el-radio-button>
          </el-radio-group>
        </div>
      </template>

      <template v-if="reports.length">
        <el-table :data="reportGrid" size="small" max-height="460" border>
          <el-table-column label="字段" width="220" fixed>
            <template #default="{ row }">{{ row.name }}</template>
          </el-table-column>
          <el-table-column v-for="(r, idx) in reports" :key="r.periodText" :label="r.periodText" min-width="130">
            <template #default="{ row }">
              <span v-if="row.values[idx]">{{ numMax(row.values[idx].value, 4) }}</span>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="muted hint">
          期别取自券商：年报与四季报的期末可能是同一天，靠期别区分；财年可能领先自然年，排序按期末日期。
          币种 {{ reports[0].currency ?? '—' }}，准则 {{ reports[0].accountingStandards ?? '—' }}。
        </div>
      </template>
      <el-empty v-else-if="!loading" description="没有这类报表：基金本来就没有财报，个股请先跑「刷新财报」" :image-size="60" />
    </el-card>

    <el-card v-if="overview && Object.keys(overview.profile).length" shadow="never" header="公司简介">
      <el-descriptions :column="3" size="small" border>
        <el-descriptions-item v-for="(v, k) in overview.profile" :key="k" :label="String(k)">{{ v }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<style scoped>
.hint { margin-top: 8px; }
.query { margin-bottom: 12px; }
.ops {
  height: 100%;
}
.ops .actions {
  margin-bottom: 8px;
}
</style>
