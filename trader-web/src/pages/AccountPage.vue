<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import NavChart from '../components/NavChart.vue'
import PageHeader from '../components/PageHeader.vue'
import StatCard from '../components/StatCard.vue'
import { daysAgoEt, errMsg, isoEt, money, signed, trend } from '../lib/format'
import {
  accountApi,
  type AccountSnapshot,
  type AuditReport,
  type HoldingSyncResult,
  type SnapshotView,
  type SyncAction,
} from '../api/account'

const view = ref<SnapshotView | null>(null)
const series = ref<AccountSnapshot[]>([])
const audit = ref<AuditReport | null>(null)
const plan = ref<HoldingSyncResult | null>(null)
const loading = ref(false)
const syncing = ref(false)
const error = ref<string | null>(null)

function statusType(s: string): 'success' | 'warning' | 'danger' {
  return s === 'OK' ? 'success' : s === 'WARN' ? 'warning' : 'danger'
}

const SOURCE_LABEL: Record<string, string> = { BAR: 'K 线收盘', SNAPSHOT: '富途快照价', NONE: '缺价' }
const ACTION_LABEL: Record<SyncAction, string> = {
  ADD: '加入 HOLDING',
  PROMOTE: 'POOL → HOLDING',
  RETURN_TO_POOL: 'HOLDING → POOL',
  REMOVE: '移出池',
}

const snapshot = computed(() => view.value?.snapshot ?? null)
const navPoints = computed(() =>
  series.value.filter((s) => s.netLiquidation !== null).map((s) => ({ time: s.asOfDate, value: s.netLiquidation as number })),
)
/** 持仓按市值占股票总市值的比例，看集中度。 */
const positions = computed(() => {
  const list = view.value?.positions ?? []
  const total = list.reduce((sum, p) => sum + (p.marketValue ?? 0), 0)
  return list.map((p) => ({ ...p, weight: total > 0 && p.marketValue !== null ? (p.marketValue / total) * 100 : null }))
})

async function load() {
  loading.value = true
  error.value = null
  try {
    const [s, a] = await Promise.all([accountApi.snapshots(daysAgoEt(365), isoEt()), accountApi.audit()])
    series.value = s
    audit.value = a
    try {
      view.value = await accountApi.latest()
    } catch (e) {
      if ((e as { response?: { status?: number } }).response?.status === 404) view.value = null
      else throw e
    }
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    loading.value = false
  }
}

async function takeSnapshot() {
  try {
    const { jobId } = await accountApi.snapshot()
    ElMessage.success(`账户快照作业 #${jobId} 已提交，几秒后刷新本页`)
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

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader title="账户" hint="盈透只读：每个交易日美东 18:00 拍快照，按收盘价估值并对账；池里的 HOLDING 由持仓自动维护">
      <template #actions>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        <el-button size="small" @click="takeSnapshot">拍快照（仅快照窗口内）</el-button>
      </template>
    </PageHeader>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />

    <el-empty v-if="!loading && !snapshot && !error" description="还没有账户快照（每个交易日美东 18:00 自动拍）" />

    <template v-if="snapshot">
      <el-row :gutter="12">
        <el-col :xs="24" :sm="12" :lg="6">
          <StatCard :value="money(snapshot.netLiquidation)" :label="`净值（${snapshot.currency ?? '—'}）`"
                    :sub="`${snapshot.asOfDate} 收盘 · ${snapshot.accountMask}`" />
        </el-col>
        <el-col :xs="24" :sm="12" :lg="6">
          <StatCard label="净值变化（含出入金）" :value-class="trend(view?.change?.netLiquidationChange)">
            <template #value>{{ view?.change ? signed(view.change.netLiquidationChange) : '—' }}</template>
            <template #sub>
              <template v-if="view?.change">
                较 {{ view.change.previousDate }}；持仓价差
                <span :class="trend(view.change.positionPnl)">{{ signed(view.change.positionPnl) }}</span>
                <el-tag v-if="view.change.positionsChanged" size="small" type="info">有买卖，近似</el-tag>
              </template>
              <template v-else>还没有上一份快照</template>
            </template>
          </StatCard>
        </el-col>
        <el-col :xs="24" :sm="12" :lg="6">
          <StatCard :value="money(snapshot.stockMarketValue)" label="股票市值（盈透）"
                    :sub="`现金 ${money(snapshot.totalCash)} · 应计股息 ${money(snapshot.accruedDividend)}`" />
        </el-col>
        <el-col :xs="24" :sm="12" :lg="6">
          <StatCard label="对账">
            <template #value><el-tag :type="statusType(snapshot.reconStatus)">{{ snapshot.reconStatus }}</el-tag></template>
            <template #sub>
              持仓 {{ snapshot.positions }} 条 · 未实现盈亏
              <span :class="trend(snapshot.unrealizedPnl)">{{ signed(snapshot.unrealizedPnl) }}</span>
            </template>
          </StatCard>
        </el-col>
      </el-row>

      <el-card v-if="navPoints.length > 1" shadow="never">
        <NavChart :points="navPoints" title="净值走势（最近一年，含出入金）" />
      </el-card>

      <el-card shadow="never" header="对账">
        <el-table :data="snapshot.recon" size="small">
          <el-table-column prop="name" label="项" width="130" />
          <el-table-column label="结果" width="90">
            <template #default="{ row }"><el-tag size="small" :type="statusType(row.status)">{{ row.status }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="detail" label="说明" min-width="400" />
        </el-table>
      </el-card>

      <el-card shadow="never" :header="`持仓（${snapshot.asOfDate} 收盘估值）`">
        <el-table :data="positions" size="small">
          <el-table-column label="代码" width="130">
            <template #default="{ row }">
              {{ row.symbol }}
              <el-tag v-if="row.cashEquivalent" size="small" type="info">现金工具</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="数量" width="100" align="right">
            <template #default="{ row }">{{ row.quantity.toLocaleString() }}</template>
          </el-table-column>
          <el-table-column label="成本价" width="100" align="right">
            <template #default="{ row }">{{ money(row.averageCost) }}</template>
          </el-table-column>
          <el-table-column label="收盘价" width="150" align="right">
            <template #default="{ row }">
              {{ money(row.price) }}
              <el-tag size="small" :type="row.priceSource === 'NONE' ? 'danger' : row.priceSource === 'SNAPSHOT' ? 'warning' : 'info'">
                {{ SOURCE_LABEL[row.priceSource] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="市值" width="130" align="right">
            <template #default="{ row }">{{ money(row.marketValue) }}</template>
          </el-table-column>
          <el-table-column label="占比" width="80" align="right">
            <template #default="{ row }">{{ row.weight === null ? '—' : row.weight.toFixed(1) + '%' }}</template>
          </el-table-column>
          <el-table-column label="未实现盈亏" min-width="120" align="right">
            <template #default="{ row }"><span :class="trend(row.unrealizedPnl)">{{ signed(row.unrealizedPnl) }}</span></template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>

    <el-card shadow="never" header="持仓同步（池里的 HOLDING）">
      <div class="actions">
        <el-button size="small" :loading="syncing" @click="previewSync">查看同步计划</el-button>
        <el-button
          size="small"
          type="primary"
          :disabled="!plan || !!plan.plan.blocked || plan.plan.changes.length === 0 || plan.applied"
          :loading="syncing"
          @click="applySync"
        >
          按计划执行
        </el-button>
        <span class="muted">快照作业每次都会先同步；这里用于盘中买卖后立刻对齐</span>
      </div>
      <template v-if="plan">
        <el-alert v-if="plan.plan.blocked" :title="plan.plan.blocked" type="warning" show-icon :closable="false" />
        <p>{{ plan.summary }}</p>
        <div v-if="plan.plan.changes.length" class="tags">
          <el-tag v-for="c in plan.plan.changes" :key="c.action + c.symbol" size="small">{{ ACTION_LABEL[c.action] }} {{ c.symbol }}</el-tag>
        </div>
        <p v-if="plan.plan.untouched.length" class="muted">不动：{{ plan.plan.untouched.join('、') }}</p>
        <el-alert v-for="e in plan.errors" :key="e" :title="e" type="error" :closable="false" />
      </template>
    </el-card>

    <el-card v-if="audit" shadow="never" :header="`账户审计 ${audit.date}（${audit.ok ? '通过' : '未通过'}）`">
      <el-table :data="audit.checks" size="small">
        <el-table-column label="" width="50">
          <template #default="{ row }">{{ row.ok ? '✅' : row.critical ? '❌' : '⚠️' }}</template>
        </el-table-column>
        <el-table-column prop="name" label="项" width="150" />
        <el-table-column prop="detail" label="说明" min-width="400" />
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.actions { margin-bottom: 8px; }
.tags { display: flex; gap: 6px; flex-wrap: wrap; }
</style>
