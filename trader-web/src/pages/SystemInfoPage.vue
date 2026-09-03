<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getHealth, getSystemInfo, type GatewayView, type SystemInfo } from '../api/system'

const info = ref<SystemInfo | null>(null)
const health = ref<string>('未知')
const loading = ref(false)
const error = ref<string | null>(null)

async function refresh() {
  loading.value = true
  error.value = null
  try {
    const [i, h] = await Promise.all([getSystemInfo(), getHealth()])
    info.value = i
    health.value = h.status
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(refresh)

function stateTag(state: GatewayView['state']): 'success' | 'info' | 'warning' | 'danger' {
  switch (state) {
    case 'CONNECTED':
      return 'success'
    case 'DISABLED':
      return 'info'
    case 'DISCONNECTED':
    case 'CONNECTING':
      return 'warning'
    default:
      return 'danger'
  }
}

function fmt(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('zh-CN', { timeZone: 'America/New_York', hour12: false }) + ' ET'
}
</script>

<template>
  <div v-loading="loading" class="page">
    <div class="page__title">
      <h2>系统信息</h2>
      <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
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
            <el-tag :type="health === 'UP' ? 'success' : 'danger'" size="small">{{ health }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="服务器时间">{{ fmt(info.serverTime) }}</el-descriptions-item>
        </el-descriptions>
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

      <el-card shadow="never" header="券商网关">
        <el-table :data="info.gateways" size="small">
          <el-table-column label="券商" width="120">
            <template #default="{ row }: { row: GatewayView }">{{ row.displayName }}（{{ row.broker }}）</template>
          </el-table-column>
          <el-table-column prop="role" label="职责" min-width="260" />
          <el-table-column label="状态" width="140">
            <template #default="{ row }: { row: GatewayView }">
              <el-tag :type="stateTag(row.state)" size="small">{{ row.state }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="detail" label="说明" min-width="220" />
          <el-table-column label="判定时刻" width="200">
            <template #default="{ row }: { row: GatewayView }">{{ fmt(row.checkedAt) }}</template>
          </el-table-column>
        </el-table>
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
  justify-content: space-between;
}
.page__title h2 {
  margin: 0;
  font-size: 18px;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: 8px 0 0;
}
</style>
