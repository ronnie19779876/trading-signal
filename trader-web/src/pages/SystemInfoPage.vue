<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getHealth, getSystemInfo, type SystemInfo } from '../api/system'
import {
  connectGateway,
  disconnectGateway,
  getAccounts,
  getEvents,
  type AccountView,
  type EventView,
  type GatewayView,
} from '../api/gateways'

const REFRESH_MS = 5000

const info = ref<SystemInfo | null>(null)
const health = ref<string>('未知')
const events = ref<EventView[]>([])
const accounts = ref<Record<string, AccountView[]>>({})
const loading = ref(false)
const error = ref<string | null>(null)
let timer: ReturnType<typeof setInterval> | null = null

async function refresh(showSpinner = false) {
  if (showSpinner) loading.value = true
  try {
    const [i, h, e] = await Promise.all([getSystemInfo(), getHealth(), getEvents(20)])
    info.value = i
    health.value = h.status
    events.value = e
    error.value = null
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function toggle(g: GatewayView) {
  try {
    if (g.state === 'CONNECTED' || g.state === 'CONNECTING' || g.state === 'RECONNECTING') {
      await disconnectGateway(g.broker)
      ElMessage.success(`${g.displayName}：已断开`)
    } else {
      await connectGateway(g.broker)
      ElMessage.success(`${g.displayName}：已发起连接`)
    }
    await refresh()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

async function loadAccounts(g: GatewayView) {
  try {
    accounts.value = { ...accounts.value, [g.broker]: await getAccounts(g.broker) }
  } catch (e) {
    ElMessage.error(`${g.displayName}账户查询失败：` + (e instanceof Error ? e.message : String(e)))
  }
}

onMounted(() => {
  refresh(true)
  timer = setInterval(() => {
    if (document.visibilityState === 'visible') refresh()
  }, REFRESH_MS)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})

function stateTag(state: GatewayView['state']): 'success' | 'info' | 'warning' | 'danger' {
  switch (state) {
    case 'CONNECTED':
      return 'success'
    case 'DISABLED':
      return 'info'
    case 'DISCONNECTED':
    case 'CONNECTING':
    case 'RECONNECTING':
      return 'warning'
    default:
      return 'danger'
  }
}

function eventTag(event: EventView['event']): 'success' | 'warning' | 'danger' {
  return event === 'ERROR' ? 'danger' : event === 'DISCONNECTED' ? 'warning' : 'success'
}

function fmt(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('zh-CN', { timeZone: 'America/New_York', hour12: false }) + ' ET'
}

function factsText(facts: Record<string, string>): string {
  return Object.entries(facts)
    .map(([k, v]) => `${k}=${v}`)
    .join('  ')
}
</script>

<template>
  <div v-loading="loading" class="page">
    <div class="page__title">
      <h2>系统信息</h2>
      <span class="muted">每 {{ REFRESH_MS / 1000 }} 秒自动刷新</span>
      <el-button size="small" :loading="loading" @click="refresh(true)">刷新</el-button>
    </div>

    <el-alert v-if="error" type="error" :title="'后端不可达：' + error" show-icon :closable="false" />

    <template v-if="info">
      <el-card shadow="never">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="应用">{{ info.application }}</el-descriptions-item>
          <el-descriptions-item label="版本">{{ info.version }}</el-descriptions-item>
          <el-descriptions-item label="构建时间">{{ fmt(info.buildTime) }}</el-descriptions-item>
          <el-descriptions-item label="环境">
            <el-tag :type="info.environment === 'PROD' ? 'danger' : 'success'" size="small">{{ info.environment }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="健康">
            <el-tag :type="health === 'UP' ? 'success' : health === 'DEGRADED' ? 'warning' : 'danger'" size="small">{{ health }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="服务器时间">{{ fmt(info.serverTime) }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card shadow="never" header="券商网关">
        <el-table :data="info.gateways" size="small">
          <el-table-column label="券商" width="110">
            <template #default="{ row }: { row: GatewayView }">{{ row.displayName }}（{{ row.broker }}）</template>
          </el-table-column>
          <el-table-column prop="role" label="职责" min-width="200" />
          <el-table-column label="状态" width="130">
            <template #default="{ row }: { row: GatewayView }">
              <el-tag :type="stateTag(row.state)" size="small">{{ row.state }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="detail" label="说明" min-width="220" />
          <el-table-column label="心跳" width="190">
            <template #default="{ row }: { row: GatewayView }">{{ fmt(row.lastHeartbeatAt) }}</template>
          </el-table-column>
          <el-table-column label="重连" width="60" prop="reconnectAttempts" />
          <el-table-column label="操作" width="170">
            <template #default="{ row }: { row: GatewayView }">
              <el-button v-if="row.enabled" size="small" :type="row.state === 'CONNECTED' ? 'warning' : 'primary'" @click="toggle(row)">
                {{ row.state === 'CONNECTED' || row.state === 'CONNECTING' || row.state === 'RECONNECTING' ? '断开' : '连接' }}
              </el-button>
              <el-button v-if="row.state === 'CONNECTED'" size="small" @click="loadAccounts(row)">账户</el-button>
              <span v-if="!row.enabled" class="muted">未启用</span>
            </template>
          </el-table-column>
          <el-table-column type="expand">
            <template #default="{ row }: { row: GatewayView }">
              <div class="expand">
                <p><b>连接自</b> {{ fmt(row.connectedSince) }}</p>
                <p><b>事实</b> <code>{{ factsText(row.facts) || '—' }}</code></p>
                <p v-if="accounts[row.broker]">
                  <b>账户</b>
                  <el-tag v-for="a in accounts[row.broker]" :key="a.maskedId" size="small" class="acct" :type="a.kind === 'LIVE' ? 'danger' : 'info'">
                    {{ a.maskedId }} {{ a.kind }} {{ a.markets.join('/') }}
                  </el-tag>
                </p>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" header="最近连接事件">
        <el-table :data="events" size="small" empty-text="暂无事件（存储未启用或尚无记录）">
          <el-table-column label="时间" width="200">
            <template #default="{ row }: { row: EventView }">{{ fmt(row.occurredAt) }}</template>
          </el-table-column>
          <el-table-column prop="broker" label="券商" width="80" />
          <el-table-column label="事件" width="140">
            <template #default="{ row }: { row: EventView }">
              <el-tag :type="eventTag(row.event)" size="small">{{ row.event }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="detail" label="说明" min-width="300" />
        </el-table>
      </el-card>

      <el-card shadow="never" header="数据库">
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="启用">{{ info.database.enabled ? '是' : '否' }}</el-descriptions-item>
          <el-descriptions-item label="库">{{ info.database.database ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="PostgreSQL">{{ info.database.serverVersion ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="环境标记">{{ info.database.marker ?? '—' }}</el-descriptions-item>
        </el-descriptions>
        <p class="muted">{{ info.database.detail }}</p>
      </el-card>

      <el-card shadow="never" header="AI 模型">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="已配置 key">{{ info.ai.configured ? '是' : '否' }}</el-descriptions-item>
          <el-descriptions-item label="模型">{{ info.ai.model }}</el-descriptions-item>
          <el-descriptions-item label="说明">{{ info.ai.detail }}</el-descriptions-item>
        </el-descriptions>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.page__title {
  display: flex;
  align-items: center;
  gap: 12px;
}
.page__title h2 {
  margin: 0;
  font-size: 18px;
  flex: 1;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: 8px 0 0;
}
.expand {
  padding: 4px 12px;
  font-size: 12px;
}
.expand p {
  margin: 4px 0;
}
.expand code {
  word-break: break-all;
}
.acct {
  margin-right: 6px;
}
</style>
