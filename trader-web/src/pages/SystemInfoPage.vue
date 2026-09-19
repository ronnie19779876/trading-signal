<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '../components/PageHeader.vue'
import { useAppStore } from '../stores/app'
import { useAutoRefresh } from '../composables/useAutoRefresh'
import { errMsg, fmtEt } from '../lib/format'
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

const app = useAppStore()
const events = ref<EventView[]>([])
const accounts = ref<Record<string, AccountView[]>>({})
const loading = ref(false)
const drawer = ref(false)
const picked = ref<GatewayView | null>(null)

const info = computed(() => app.info)
const health = computed(() => app.health)
const error = computed(() => app.error)

async function refresh(showSpinner = false) {
  if (showSpinner) loading.value = true
  try {
    const [, e] = await Promise.all([app.refresh(), getEvents(20)])
    events.value = e
  } finally {
    loading.value = false
  }
}

/** 连接中与重连中也归到"可以断开"这一侧，按钮的文案与颜色用同一个判断。 */
function busy(state: GatewayView['state']): boolean {
  return state === 'CONNECTED' || state === 'CONNECTING' || state === 'RECONNECTING'
}

async function toggle(g: GatewayView) {
  try {
    if (busy(g.state)) {
      await disconnectGateway(g.broker)
      ElMessage.success(`${g.displayName}：已断开`)
    } else {
      await connectGateway(g.broker)
      ElMessage.success(`${g.displayName}：已发起连接`)
    }
    await refresh()
  } catch (e) {
    ElMessage.error(errMsg(e))
  }
}

/** 连接明细原先塞在展开行里，展开箭头是表格最后一列，窄一点就被挤出视口，点"账户"像没反应。 */
function openDetail(g: GatewayView) {
  picked.value = g
  drawer.value = true
}

/** 抽屉里显示的是最新一轮拉回来的网关，不是打开时的那份快照。 */
const pickedLive = computed(() => info.value?.gateways.find((g) => g.broker === picked.value?.broker) ?? picked.value)

async function loadAccounts(g: GatewayView) {
  try {
    accounts.value = { ...accounts.value, [g.broker]: await getAccounts(g.broker) }
  } catch (e) {
    ElMessage.error(`${g.displayName}账户查询失败：` + errMsg(e))
  }
}

useAutoRefresh(() => refresh(), REFRESH_MS, { immediate: false })
refresh(true)

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

function factsText(facts: Record<string, string>): string {
  return Object.entries(facts)
    .map(([k, v]) => `${k}=${v}`)
    .join('  ')
}
</script>

<template>
  <div v-loading="loading" class="page">
    <PageHeader title="系统信息" :hint="`每 ${REFRESH_MS / 1000} 秒自动刷新`">
      <template #actions>
        <el-button size="small" :loading="loading" @click="refresh(true)">刷新</el-button>
        <router-link to="/marketdata"><el-button size="small" text type="primary">行情数据底座 →</el-button></router-link>
      </template>
    </PageHeader>

    <el-alert v-if="error" type="error" :title="'后端不可达：' + error" show-icon :closable="false" />

    <template v-if="info">
      <el-card shadow="never">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="应用">{{ info.application }}</el-descriptions-item>
          <el-descriptions-item label="版本">{{ info.version }}</el-descriptions-item>
          <el-descriptions-item label="构建时间">{{ fmtEt(info.buildTime) }}</el-descriptions-item>
          <el-descriptions-item label="环境">
            <el-tag :type="info.environment === 'PROD' ? 'danger' : 'success'" size="small">{{ info.environment }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="健康">
            <el-tag :type="health === 'UP' ? 'success' : health === 'DEGRADED' ? 'warning' : 'danger'" size="small">{{ health }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="服务器时间">{{ fmtEt(info.serverTime) }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card shadow="never" header="券商网关">
        <el-table :data="info.gateways" size="small" highlight-current-row @row-click="openDetail">
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
            <template #default="{ row }: { row: GatewayView }">{{ fmtEt(row.lastHeartbeatAt) }}</template>
          </el-table-column>
          <el-table-column label="重连" width="60" prop="reconnectAttempts" />
          <el-table-column label="操作" min-width="180">
            <template #default="{ row }: { row: GatewayView }">
              <el-button v-if="row.enabled" size="small" :type="busy(row.state) ? 'warning' : 'primary'" @click.stop="toggle(row)">
                {{ busy(row.state) ? '断开' : '连接' }}
              </el-button>
              <el-button size="small" @click.stop="openDetail(row)">详情</el-button>
              <span v-if="!row.enabled" class="muted">未启用</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-drawer v-model="drawer" size="40%" :title="pickedLive ? `${pickedLive.displayName}（${pickedLive.broker}）` : '网关'">
        <div v-if="pickedLive" class="detail">
          <el-descriptions :column="1" border size="small" label-width="88">
            <el-descriptions-item label="状态">
              <el-tag :type="stateTag(pickedLive.state)" size="small">{{ pickedLive.state }}</el-tag>
              <span class="muted">{{ pickedLive.detail }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="职责">{{ pickedLive.role }}</el-descriptions-item>
            <el-descriptions-item label="连接自">{{ fmtEt(pickedLive.connectedSince) }}</el-descriptions-item>
            <el-descriptions-item label="最近心跳">{{ fmtEt(pickedLive.lastHeartbeatAt) }}</el-descriptions-item>
            <el-descriptions-item label="重连次数">{{ pickedLive.reconnectAttempts }}</el-descriptions-item>
            <el-descriptions-item label="事实"><code>{{ factsText(pickedLive.facts) || '—' }}</code></el-descriptions-item>
          </el-descriptions>

          <div class="actions">
            <el-button size="small" :disabled="pickedLive.state !== 'CONNECTED'" @click="loadAccounts(pickedLive)">查询账户</el-button>
            <span class="muted">账户号只回打码后的尾号</span>
          </div>
          <div v-if="accounts[pickedLive.broker]" class="tags">
            <el-tag v-for="a in accounts[pickedLive.broker]" :key="a.maskedId" size="small" :type="a.kind === 'LIVE' ? 'danger' : 'info'">
              {{ a.maskedId }} {{ a.kind }} {{ a.markets.join('/') }}
            </el-tag>
            <span v-if="!accounts[pickedLive.broker].length" class="muted">没有账户</span>
          </div>
        </div>
      </el-drawer>

      <el-card shadow="never" header="最近连接事件">
        <el-table :data="events" size="small" empty-text="暂无事件（存储未启用或尚无记录）">
          <el-table-column label="时间" width="200">
            <template #default="{ row }: { row: EventView }">{{ fmtEt(row.occurredAt) }}</template>
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
.detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.detail code {
  word-break: break-all;
  font-size: 12px;
}
.tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
</style>
