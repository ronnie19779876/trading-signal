<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useAppStore } from '../../stores/app'
import { errMsg, fmtEt, timeEt } from '../../lib/format'
import { GATEWAY_STATE, ago, jobLabel } from '../../lib/system'
import { auditCheckLabel } from '../../lib/audit'
import { connectGateway, disconnectGateway, getAccounts, type AccountView, type GatewayView } from '../../api/gateways'
import { getBarAudit } from '../../api/marketdata'
import { fundamentalsApi } from '../../api/fundamentals'
import { accountApi, type AuditReport } from '../../api/account'
import { signalsApi } from '../../api/signals'
import { getHealth } from '../../api/system'

/**
 * 概况：运行状态、两家网关、巡检（点按钮才跑，与 check-daily.sh 同源）、各类作业最近一次（取自健康检查）。
 * 数据来自全站状态（页头也用它，5 秒一轮），这里不另外轮询。
 */
const app = useAppStore()
const info = computed(() => app.info)
const health = computed(() => app.healthDetail)
const now = ref(Date.now())
const clock = window.setInterval(() => (now.value = Date.now()), 1000)
onBeforeUnmount(() => window.clearInterval(clock))

// ---- 网关 ----
/** 连接中与重连中也归到"可以断开"这一侧。 */
const busy = (state: GatewayView['state']) => state === 'CONNECTED' || state === 'CONNECTING' || state === 'RECONNECTING'
async function toggle(g: GatewayView) {
  try {
    if (busy(g.state)) {
      await disconnectGateway(g.broker)
      ElMessage.success(`${g.displayName}：已断开`)
    } else {
      await connectGateway(g.broker)
      ElMessage.success(`${g.displayName}：已发起连接`)
    }
    await app.refresh()
  } catch (e) {
    ElMessage.error(errMsg(e))
  }
}
const drawer = ref(false)
const pickedBroker = ref<string | null>(null)
/** 抽屉显示最新一轮拉回来的网关，不是打开时的快照。 */
const picked = computed(() => info.value?.gateways.find((g) => g.broker === pickedBroker.value) ?? null)
const accounts = ref<Record<string, AccountView[]>>({})
function openDetail(g: GatewayView) {
  pickedBroker.value = g.broker
  drawer.value = true
}
async function loadAccounts(g: GatewayView) {
  try {
    accounts.value = { ...accounts.value, [g.broker]: await getAccounts(g.broker) }
  } catch (e) {
    ElMessage.error(`${g.displayName}账户查询失败：` + errMsg(e))
  }
}
const factsText = (facts: Record<string, string>) => Object.entries(facts).map(([k, v]) => `${k}=${v}`).join('  ')

// ---- 运行状态附带：磁盘 ----
const disk = computed(() => {
  const d = health.value?.components?.diskSpace?.details as { free?: number; total?: number } | undefined
  return d?.free && d?.total ? `${(d.free / 1e9).toFixed(0)} GB 空闲 / ${(d.total / 1e9).toFixed(0)} GB` : '—'
})

// ---- 各类作业最近一次：健康检查里的 "OK @ 2026-09-18T21:30:00Z：摘要" ----
const JOB_ORDER = ['DAILY_INCREMENT', 'VALUATION_SNAPSHOT', 'ACCOUNT_SNAPSHOT', 'SIGNAL_EVALUATION', 'CATCHUP_CHECK', 'UNIVERSE_SYNC', 'FINANCIALS_REFRESH']
const lastJobs = computed(() => {
  const d = (health.value?.components?.jobs?.details ?? {}) as Record<string, string>
  const keys = [...JOB_ORDER.filter((k) => k in d), ...Object.keys(d).filter((k) => !JOB_ORDER.includes(k))]
  return keys.map((k) => {
    // 时间本身带冒号（21:30:00），要以 Z 结尾再接分隔符，否则会截在时间中间
    const m = /^(\w+) @ (\S+?Z)\s*[：:]\s*(.*)$/s.exec(d[k])
    return { job: k, status: m?.[1] ?? '?', at: m?.[2] ?? null, summary: m ? m[3] : d[k] }
  })
})
const jobsStatus = computed(() => health.value?.components?.jobs?.status ?? null)

// ---- 巡检：点按钮才跑 ----
interface Section { title: string; report: AuditReport | null; error: string | null }
const inspecting = ref(false)
const inspectedAt = ref<string | null>(null)
const sections = ref<Section[]>([])
const healthNow = ref<{ status: string; bad: string[] } | null>(null)
async function inspect() {
  inspecting.value = true
  const wrap = async (title: string, fn: () => Promise<AuditReport>): Promise<Section> => {
    try {
      return { title, report: await fn(), error: null }
    } catch (e) {
      return { title, report: null, error: errMsg(e) }
    }
  }
  try {
    const [bars, fund, acct, sig, h] = await Promise.all([
      wrap('日线数据审计', () => getBarAudit()),
      wrap('基本面审计', () => fundamentalsApi.audit()),
      wrap('账户审计', () => accountApi.audit()),
      wrap('信号审计', () => signalsApi.audit()),
      getHealth().catch(() => null),
    ])
    sections.value = [bars, fund, acct, sig]
    healthNow.value = h
      ? { status: h.status, bad: Object.entries(h.components ?? {}).filter(([, c]) => c.status !== 'UP').map(([k, c]) => `${k}=${c.status}`) }
      : { status: '不可达', bad: [] }
    inspectedAt.value = new Date().toISOString()
  } finally {
    inspecting.value = false
  }
}
const failedChecks = (r: AuditReport) => r.checks.filter((c) => !c.ok)
</script>

<template>
  <div class="tab">
    <template v-if="info">
      <!-- 运行状态 -->
      <section class="panel">
        <div class="panel__head"><h3>运行状态</h3><span class="panel__src">页头与这里同一份数据，5 秒刷新</span></div>
        <div class="kv-grid">
          <div class="kv"><label>版本</label><b>{{ info.version }}</b></div>
          <div class="kv"><label>环境</label><b :class="{ prod: info.environment === 'PROD' }">{{ info.environment === 'PROD' ? '生产' : info.environment === 'DEV' ? '开发' : info.environment }}</b></div>
          <div class="kv"><label>健康</label><b :class="app.health === 'UP' ? 'ok' : 'bad'">{{ app.health === 'UP' ? '正常' : app.health }}</b></div>
          <div class="kv"><label>构建时间</label><b class="small">{{ fmtEt(info.buildTime) }}</b></div>
          <div class="kv"><label>服务器时间</label><b class="small">{{ fmtEt(info.serverTime) }}</b></div>
          <div class="kv"><label>数据库</label><b class="small">{{ info.database.enabled ? `PostgreSQL ${info.database.serverVersion ?? ''} · ${info.database.marker ?? '—'}` : '未启用' }}</b></div>
          <div class="kv"><label>AI 模型</label><b class="small">{{ info.ai.configured ? info.ai.model : '未配置密钥' }}</b></div>
          <div class="kv"><label>磁盘</label><b class="small">{{ disk }}</b></div>
        </div>
      </section>

      <!-- 网关 -->
      <div class="gws">
        <section v-for="g in info.gateways" :key="g.broker" class="panel gw">
          <div class="panel__head">
            <h3>{{ g.displayName }}</h3>
            <el-tag size="small" :type="GATEWAY_STATE[g.state].type">{{ GATEWAY_STATE[g.state].text }}</el-tag>
            <span class="panel__src">{{ g.role }}</span>
            <span class="panel__grow" />
            <el-button v-if="g.enabled" size="small" :type="busy(g.state) ? 'warning' : 'primary'" plain @click="toggle(g)">{{ busy(g.state) ? '断开' : '连接' }}</el-button>
            <el-button size="small" @click="openDetail(g)">详情</el-button>
          </div>
          <div class="kv-grid">
            <div class="kv"><label>说明</label><b class="small">{{ g.detail || '—' }}</b></div>
            <div class="kv"><label>已连接</label><b class="small">{{ g.connectedSince ? ago(g.connectedSince, now).replace('前', '') : '—' }}</b></div>
            <div class="kv"><label>最近心跳</label><b class="small">{{ ago(g.lastHeartbeatAt, now) }}</b></div>
            <div class="kv"><label>重连次数</label><b>{{ g.reconnectAttempts }}</b></div>
          </div>
        </section>
      </div>

      <!-- 巡检 -->
      <section class="panel">
        <div class="panel__head">
          <h3>巡检</h3>
          <span class="panel__src">与 check-daily.sh 同源：日线、基本面、账户、信号四段审计 + 运行健康；默认审最近一个已收盘交易日（美东 19:00 之后跑才完整）</span>
          <span class="panel__grow" />
          <span v-if="inspectedAt" class="panel__src">上次执行 {{ timeEt(inspectedAt) }} ET</span>
          <el-button size="small" type="primary" :loading="inspecting" @click="inspect">执行巡检</el-button>
        </div>
        <template v-if="sections.length">
          <div v-for="s in sections" :key="s.title" class="insp">
            <div class="insp__row">
              <b class="insp__title">{{ s.title }}</b>
              <template v-if="s.report">
                <span class="muted">{{ s.report.date }}</span>
                <el-tag size="small" :type="s.report.ok ? 'success' : 'danger'">{{ s.report.ok ? '通过' : '未通过' }}</el-tag>
                <span v-if="!failedChecks(s.report).length" class="muted">{{ s.report.checks.length }} 项全部通过</span>
              </template>
              <el-tag v-else size="small" type="danger">接口不可达：{{ s.error }}</el-tag>
            </div>
            <div v-for="c in s.report ? failedChecks(s.report) : []" :key="c.name" class="insp__check" :class="{ critical: c.critical }">
              {{ c.critical ? '关键' : '提示' }} · {{ auditCheckLabel(c.name) }}：{{ c.detail }}
            </div>
          </div>
          <div v-if="healthNow" class="insp">
            <div class="insp__row">
              <b class="insp__title">运行健康</b>
              <el-tag size="small" :type="healthNow.status === 'UP' ? 'success' : 'danger'">{{ healthNow.status === 'UP' ? '正常' : healthNow.status }}</el-tag>
              <span v-if="healthNow.bad.length" class="bad">{{ healthNow.bad.join('、') }}</span>
            </div>
          </div>
        </template>
        <p v-else class="muted">点"执行巡检"才会调四个审计接口（只读，一般一两秒）。</p>
      </section>

      <!-- 作业最近一次 -->
      <section class="panel">
        <div class="panel__head">
          <h3>各类作业最近一次</h3>
          <el-tag v-if="jobsStatus" size="small" :type="jobsStatus === 'UP' ? 'success' : 'danger'">{{ jobsStatus === 'UP' ? '正常' : jobsStatus }}</el-tag>
          <span class="panel__src">取自健康检查；完整记录看"跑批"</span>
        </div>
        <div class="dtable-wrap">
          <table class="dtable">
            <thead><tr><th>作业</th><th>结果</th><th>时间</th><th>摘要</th></tr></thead>
            <tbody>
              <tr v-for="j in lastJobs" :key="j.job">
                <td><b>{{ jobLabel(j.job) }}</b></td>
                <td><el-tag size="small" :type="j.status === 'OK' ? 'success' : j.status === 'PARTIAL' ? 'warning' : j.status === 'SKIPPED' ? 'info' : 'danger'">{{ j.status === 'OK' ? '成功' : j.status === 'PARTIAL' ? '部分成功' : j.status === 'SKIPPED' ? '跳过' : j.status }}</el-tag></td>
                <td class="muted-cell">{{ j.at ? fmtEt(j.at) : '—' }}</td>
                <td class="wrap">{{ j.summary }}</td>
              </tr>
              <tr v-if="!lastJobs.length"><td colspan="4" class="empty">还没有作业记录（开发实例不跑定时作业）</td></tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>

    <el-drawer v-model="drawer" size="40%" :title="picked ? picked.displayName : '网关'">
      <div v-if="picked" class="detail">
        <el-descriptions :column="1" border size="small" label-width="88">
          <el-descriptions-item label="状态">
            <el-tag :type="GATEWAY_STATE[picked.state].type" size="small">{{ GATEWAY_STATE[picked.state].text }}</el-tag>
            <span class="muted">{{ picked.detail }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="职责">{{ picked.role }}</el-descriptions-item>
          <el-descriptions-item label="连接自">{{ fmtEt(picked.connectedSince) }}</el-descriptions-item>
          <el-descriptions-item label="最近心跳">{{ fmtEt(picked.lastHeartbeatAt) }}</el-descriptions-item>
          <el-descriptions-item label="重连次数">{{ picked.reconnectAttempts }}</el-descriptions-item>
          <el-descriptions-item label="事实"><code>{{ factsText(picked.facts) || '—' }}</code></el-descriptions-item>
        </el-descriptions>
        <div class="actions">
          <el-button size="small" :disabled="picked.state !== 'CONNECTED'" @click="loadAccounts(picked)">查询账户</el-button>
          <span class="muted">账户号只回打码后的尾号</span>
        </div>
        <div v-if="accounts[picked.broker]" class="tags">
          <el-tag v-for="a in accounts[picked.broker]" :key="a.maskedId" size="small" :type="a.kind === 'LIVE' ? 'danger' : 'info'">
            {{ a.maskedId }} {{ a.kind === 'LIVE' ? '实盘' : '模拟' }} {{ a.markets.join('/') }}
          </el-tag>
          <span v-if="!accounts[picked.broker].length" class="muted">没有账户</span>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.tab { display: flex; flex-direction: column; gap: 12px; }
.kv b.small { font-size: 12px; font-weight: 500; }
.prod { color: var(--el-color-danger); }
.ok { color: var(--el-color-success); }
.bad { color: var(--el-color-danger); }
.gws { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px; }
@media (max-width: 1100px) { .gws { grid-template-columns: minmax(0, 1fr); } }
.insp { padding: 6px 0; border-top: 1px solid var(--el-border-color-extra-light); }
.insp:first-of-type { border-top: 0; }
.insp__row { display: flex; align-items: center; gap: 10px; font-size: 13px; }
.insp__title { width: 110px; }
.insp__check { font-size: 12px; margin: 4px 0 0 120px; color: var(--el-color-warning); }
.insp__check.critical { color: var(--el-color-danger); }
.dtable { font-size: 13px; }
.muted-cell { color: var(--el-text-color-secondary); }
.wrap { white-space: normal; }
.detail { display: flex; flex-direction: column; gap: 12px; }
.detail code { word-break: break-all; font-size: 12px; }
.tags { display: flex; gap: 6px; flex-wrap: wrap; }
</style>
