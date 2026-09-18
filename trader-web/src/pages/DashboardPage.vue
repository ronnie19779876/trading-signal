<script setup lang="ts">
import { computed, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import StatCard from '../components/StatCard.vue'
import NavChart from '../components/NavChart.vue'
import SignalDetailDrawer from '../components/signals/SignalDetailDrawer.vue'
import { ROLE_LABEL, SIGNAL_STATUS_LABEL, SIGNAL_STATUS_TYPE, STANCE_LABEL } from '../components/signals/format'
import { useAppStore } from '../stores/app'
import { useAutoRefresh } from '../composables/useAutoRefresh'
import { daysAgoEt, errMsg, fmtEt, isoEt, money, num, pct, signed, signedR, todayEt, trend } from '../lib/format'
import { signalsApi, type SignalStatus, type SignalView } from '../api/signals'
import { accountApi, type AccountSnapshot, type AuditReport, type SnapshotView } from '../api/account'
import { getCoverage, getJobs, type CoverageView, type JobRun } from '../api/marketdata'
import { fundamentalsApi, type FundamentalsCoverage } from '../api/fundamentals'
import type { GatewayView } from '../api/gateways'

/**
 * 首页：一屏看完"今天系统干了什么、账户怎么样、有没有出问题"。
 * 全部复用既有接口并行拉取，不新增后端接口。
 */
const RECENT_DAYS = 7
const NAV_DAYS = 180
/** 数据超过这么多自然日没动就标黄。只是提醒，不判交易日。 */
const STALE_DAYS = 3

const app = useAppStore()

const signalAudit = ref<AuditReport | null>(null)
const accountAudit = ref<AuditReport | null>(null)
const recent = ref<SignalView[]>([])
const latest = ref<SnapshotView | null>(null)
const navSeries = ref<AccountSnapshot[]>([])
const jobs = ref<JobRun[]>([])
const barCoverage = ref<CoverageView | null>(null)
const fundCoverage = ref<FundamentalsCoverage | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

const drawer = ref(false)
const pickedId = ref<number | null>(null)

/** 定时刷新不打转圈：每分钟盖一次全页遮罩太晃眼，只有手点刷新和首次加载才转。 */
async function load(showSpinner = false) {
  if (showSpinner) loading.value = true
  error.value = null
  try {
    const [sa, aa, list, series, j, bc, fc] = await Promise.all([
      signalsApi.audit(),
      accountApi.audit(),
      signalsApi.list({ from: daysAgoEt(RECENT_DAYS), to: todayEt(), scope: 'all' }),
      accountApi.snapshots(daysAgoEt(NAV_DAYS), isoEt()),
      getJobs(8),
      getCoverage(),
      fundamentalsApi.coverage(),
    ])
    signalAudit.value = sa
    accountAudit.value = aa
    recent.value = [...list].sort((a, b) => (a.signal.tradeDate < b.signal.tradeDate ? 1 : -1))
    navSeries.value = series
    jobs.value = j.recent
    barCoverage.value = bc
    fundCoverage.value = fc
    // 还没拍过快照时是 404，不当错误。
    try {
      latest.value = await accountApi.latest()
    } catch (e) {
      if ((e as { response?: { status?: number } }).response?.status === 404) latest.value = null
      else throw e
    }
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    loading.value = false
  }
}

useAutoRefresh(() => load(), 60_000, { immediate: false })
load(true)

const summary = computed(() => signalAudit.value?.summary ?? {})
const snapshot = computed(() => latest.value?.snapshot ?? null)
const navPoints = computed(() =>
  navSeries.value.filter((s) => s.netLiquidation !== null).map((s) => ({ time: s.asOfDate, value: s.netLiquidation as number })),
)
const lastJob = computed(() => jobs.value[0] ?? null)

/** 两份审计里没通过的项合起来看，关键的排前面。 */
const problems = computed(() => {
  const rows = [
    ...(signalAudit.value?.checks ?? []).filter((c) => !c.ok).map((c) => ({ ...c, from: '信号' })),
    ...(accountAudit.value?.checks ?? []).filter((c) => !c.ok).map((c) => ({ ...c, from: '账户' })),
  ]
  return rows.sort((a, b) => Number(b.critical) - Number(a.critical))
})

function daysSince(date: string | null | undefined): number | null {
  if (!date) return null
  return Math.round((new Date(todayEt()).getTime() - new Date(date).getTime()) / 86_400_000)
}

/** 数据新鲜度：几条时间线放一起，一眼看出哪条停了。 */
const freshness = computed(() => [
  { name: '日 K 线', date: barCoverage.value?.latest ?? null, hint: `${barCoverage.value?.rows.toLocaleString() ?? '—'} 行` },
  { name: '估值快照', date: fundCoverage.value?.latestDate ?? null, hint: `${fundCoverage.value?.withValuationOnDate ?? '—'} / ${fundCoverage.value?.targets ?? '—'} 只` },
  { name: '账户快照', date: snapshot.value?.asOfDate ?? null, hint: snapshot.value ? `${snapshot.value.positions} 条持仓` : '还没拍过' },
  { name: '信号评估', date: signalAudit.value?.date ?? null, hint: summary.value.tradingDay === false ? '休市' : `${summary.value.evaluations ?? 0} 只` },
].map((f) => ({ ...f, age: daysSince(f.date) })))

const gatewayTag = (g: GatewayView) => (g.state === 'CONNECTED' ? 'success' : g.state === 'DISABLED' ? 'info' : 'warning')

function jobTag(s: JobRun['status']): 'success' | 'warning' | 'danger' | 'info' {
  return s === 'OK' ? 'success' : s === 'PARTIAL' ? 'warning' : s === 'FAILED' ? 'danger' : 'info'
}

function open(row: SignalView) {
  pickedId.value = row.signal.id
  drawer.value = true
}
</script>

<template>
  <div v-loading="loading" class="page">
    <PageHeader title="仪表盘" :hint="`今天美东 ${todayEt()}；每分钟自动刷新`">
      <template #actions>
        <el-button size="small" :loading="loading" @click="load(true)">刷新</el-button>
      </template>
    </PageHeader>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />

    <el-row :gutter="12">
      <el-col :xs="24" :sm="12" :lg="6">
        <StatCard :label="summary.tradingDay === false ? '今日休市' : '今日新信号'">
          <template #value>
            <router-link to="/signals" class="link">{{ summary.tradingDay === false ? '—' : (summary.signals ?? 0) }}</router-link>
          </template>
          <template #sub>
            <template v-if="summary.tradingDay !== false">
              评估 {{ summary.evaluations ?? 0 }} / {{ summary.targets ?? '—' }} · 池与持仓 {{ summary.poolSignals ?? 0 }} ·
              AI 否决 {{ summary.aiVetoes ?? 0 }}
            </template>
            <template v-else>非交易日不评估</template>
          </template>
        </StatCard>
      </el-col>

      <el-col :xs="24" :sm="12" :lg="6">
        <StatCard :label="`净值（${snapshot?.currency ?? '—'}）`" :value-class="trend(latest?.change?.netLiquidationChange)">
          <template #value>
            <router-link to="/account" class="link">{{ money(snapshot?.netLiquidation) }}</router-link>
          </template>
          <template #sub>
            <template v-if="snapshot">
              {{ snapshot.asOfDate }} 收盘
              <template v-if="latest?.change">
                · 较上一份
                <span :class="trend(latest.change.netLiquidationChange)">{{ signed(latest.change.netLiquidationChange) }}</span>
              </template>
            </template>
            <template v-else>还没有账户快照</template>
          </template>
        </StatCard>
      </el-col>

      <el-col :xs="24" :sm="12" :lg="6">
        <StatCard label="跑批">
          <template #value>
            <el-tag v-if="app.running" type="warning" effect="plain">运行中</el-tag>
            <el-tag v-else-if="lastJob" :type="jobTag(lastJob.status)" effect="plain">{{ lastJob.status }}</el-tag>
            <span v-else>—</span>
          </template>
          <template #sub>
            <template v-if="app.running">#{{ app.running.id }} {{ app.running.job }}：{{ app.running.progress || '…' }}</template>
            <template v-else-if="lastJob">最近 #{{ lastJob.id }} {{ lastJob.job }} · {{ fmtEt(lastJob.finishedAt ?? lastJob.startedAt) }}</template>
            <template v-else>暂无作业记录</template>
          </template>
        </StatCard>
      </el-col>

      <el-col :xs="24" :sm="12" :lg="6">
        <StatCard label="券商网关">
          <template #value>
            <span class="gateways">
              <el-tag v-for="g in app.info?.gateways ?? []" :key="g.broker" size="small" :type="gatewayTag(g)" effect="plain">
                {{ g.broker }}
              </el-tag>
              <span v-if="!app.info">—</span>
            </span>
          </template>
          <template #sub>
            健康 {{ app.health }} ·
            <router-link to="/system" class="link">系统信息</router-link>
          </template>
        </StatCard>
      </el-col>
    </el-row>

    <el-card v-if="problems.length" shadow="never" header="需要处理">
      <div v-for="c in problems" :key="c.from + c.name" class="problem" :class="{ 'problem--critical': c.critical }">
        <el-tag size="small" :type="c.critical ? 'danger' : 'warning'" effect="plain">{{ c.critical ? '关键' : '提示' }}</el-tag>
        <span class="problem__from">{{ c.from }}</span>
        <b>{{ c.name }}</b>
        <span>{{ c.detail }}</span>
        <span v-if="c.samples.length" class="muted">（{{ c.samples.slice(0, 8).join('、') }}）</span>
      </div>
    </el-card>
    <el-alert v-else-if="signalAudit && accountAudit" type="success" show-icon :closable="false"
              :title="`信号与账户审计都通过（${signalAudit.date}）`" />

    <el-card shadow="never" header="数据新鲜度">
      <div class="fresh">
        <div v-for="f in freshness" :key="f.name" class="fresh__item">
          <div class="fresh__name">{{ f.name }}</div>
          <div class="fresh__date" :class="{ stale: f.age !== null && f.age > STALE_DAYS }">
            {{ f.date ?? '—' }}
            <span v-if="f.age !== null && f.age > 0" class="muted">（{{ f.age }} 天前）</span>
          </div>
          <div class="muted">{{ f.hint }}</div>
        </div>
      </div>
    </el-card>

    <el-row :gutter="12">
      <el-col :xs="24" :lg="14">
        <el-card shadow="never" :header="`最近 ${RECENT_DAYS} 天的信号（${recent.length} 条，点一行看详情）`">
          <el-table :data="recent" size="small" max-height="360" empty-text="这几天没有信号" @row-click="open">
            <el-table-column label="判定日" width="100"><template #default="{ row }">{{ row.signal.tradeDate }}</template></el-table-column>
            <el-table-column label="代码" width="80"><template #default="{ row }"><b>{{ row.signal.symbol }}</b></template></el-table-column>
            <el-table-column label="角色" width="60">
              <template #default="{ row }">{{ ROLE_LABEL[row.signal.role as keyof typeof ROLE_LABEL] }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="SIGNAL_STATUS_TYPE[row.signal.status as SignalStatus]">
                  {{ SIGNAL_STATUS_LABEL[row.signal.status as SignalStatus] }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="收盘" width="85" align="right"><template #default="{ row }">{{ num(row.signal.close) }}</template></el-table-column>
            <el-table-column label="止损距离" width="90" align="right">
              <template #default="{ row }">{{ pct(row.signal.stopDistance, 1) }}</template>
            </el-table-column>
            <el-table-column label="AI" width="60">
              <template #default="{ row }">
                {{ row.signal.aiStance ? STANCE_LABEL[row.signal.aiStance as keyof typeof STANCE_LABEL] ?? row.signal.aiStance : '—' }}
              </template>
            </el-table-column>
            <el-table-column label="账本 R" min-width="80" align="right">
              <template #default="{ row }">
                <span v-if="row.base" :class="trend(row.base.rMultiple)">{{ signedR(row.base.rMultiple) }}</span>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="10">
        <el-card shadow="never" :header="`净值走势（最近 ${NAV_DAYS} 天，含出入金）`">
          <NavChart v-if="navPoints.length > 1" :points="navPoints" />
          <el-empty v-else :image-size="60"
                    :description="navPoints.length === 1 ? '只有一份快照，攒够两天才画得出走势' : '还没有账户快照'" />
        </el-card>
      </el-col>
    </el-row>

    <SignalDetailDrawer v-model="drawer" :signal-id="pickedId" @changed="load()" />
  </div>
</template>

<style scoped>
.link {
  color: inherit;
  text-decoration: none;
}
.link:hover {
  color: var(--el-color-primary);
}
.gateways {
  display: inline-flex;
  gap: 6px;
  flex-wrap: wrap;
}
.problem {
  font-size: 13px;
  line-height: 1.9;
  display: flex;
  gap: 8px;
  align-items: baseline;
  flex-wrap: wrap;
}
.problem--critical b {
  color: var(--el-color-danger);
}
.problem__from {
  color: var(--el-text-color-secondary);
}
.fresh {
  display: flex;
  gap: 32px;
  flex-wrap: wrap;
}
.fresh__name {
  color: var(--el-text-color-regular);
  font-size: 13px;
}
.fresh__date {
  font-size: 18px;
  font-weight: 600;
  margin: 2px 0;
}
.fresh__date.stale {
  color: var(--el-color-warning);
}
</style>
