<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { signalsApi, type SignalStatus, type SignalView } from '../../api/signals'
import SignalDetailDrawer from './SignalDetailDrawer.vue'
import {
  ROLE_LABEL, SIGNAL_STATUS_LABEL, SIGNAL_STATUS_TYPE, STANCE_LABEL, TRACK_LABEL, daysAgo, errMsg, iso, num, pct, signedR, trend,
} from './format'

/** 信号列表：状态、来源、范围过滤；已看过 / 不做；点一行看详情。 */
const range = ref<[string, string]>([daysAgo(30), iso(new Date())])
const statuses = ref<SignalStatus[]>([])
const origin = ref<string>('')
const scope = ref<'pool' | 'all'>('pool')
const rows = ref<SignalView[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const drawer = ref(false)
const pickedId = ref<number | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    rows.value = await signalsApi.list({
      from: range.value?.[0], to: range.value?.[1], status: statuses.value.join(','), scope: scope.value, origin: origin.value,
    })
  } catch (e) {
    error.value = errMsg(e)
    rows.value = []
  } finally {
    loading.value = false
  }
}

watch([range, statuses, origin, scope], load, { immediate: true })

function open(row: SignalView) {
  pickedId.value = row.signal.id
  drawer.value = true
}

async function quick(row: SignalView, to: 'ACKNOWLEDGED' | 'DISMISSED') {
  let note = ''
  try {
    const r = await ElMessageBox.prompt(
      `把 ${row.signal.symbol} ${row.signal.tradeDate} 的信号标为「${SIGNAL_STATUS_LABEL[to]}」？可以写一句备注。`,
      '改信号状态',
      { confirmButtonText: '确定', cancelButtonText: '取消', inputPlaceholder: '备注（可空）' },
    )
    note = (r as { value: string }).value ?? ''
  } catch {
    return
  }
  try {
    await signalsApi.changeStatus(row.signal.id, to, note || undefined)
    ElMessage.success('已更新')
    await load()
  } catch (e) {
    ElMessage.error(errMsg(e))
  }
}

defineExpose({ load })
</script>

<template>
  <div v-loading="loading" class="tab">
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <div class="toolbar">
      <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" size="small" style="width: 240px" :clearable="false" />
      <el-select v-model="statuses" multiple collapse-tags size="small" placeholder="状态" style="width: 170px">
        <el-option v-for="(label, k) in SIGNAL_STATUS_LABEL" :key="k" :label="label" :value="k" />
      </el-select>
      <el-select v-model="origin" size="small" clearable placeholder="来源" style="width: 100px">
        <el-option label="实盘" value="LIVE" />
        <el-option label="补跑" value="BACKFILL" />
      </el-select>
      <el-radio-group v-model="scope" size="small">
        <el-radio-button value="pool">池与持仓</el-radio-button>
        <el-radio-button value="all">全部</el-radio-button>
      </el-radio-group>
      <span class="muted">{{ rows.length }} 条 · 价位为判定日口径</span>
    </div>

    <el-empty v-if="!loading && !rows.length && !error" description="这个范围内没有信号" />
    <el-table v-else :data="rows" size="small" max-height="620" @row-click="open">
      <el-table-column label="判定日" width="100"><template #default="{ row }">{{ row.signal.tradeDate }}</template></el-table-column>
      <el-table-column label="代码" width="80" fixed><template #default="{ row }"><b>{{ row.signal.symbol }}</b></template></el-table-column>
      <el-table-column label="角色" width="60"><template #default="{ row }">{{ ROLE_LABEL[row.signal.role as keyof typeof ROLE_LABEL] }}</template></el-table-column>
      <el-table-column label="来源" width="60"><template #default="{ row }">{{ row.signal.origin === 'LIVE' ? '实盘' : '补跑' }}</template></el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="SIGNAL_STATUS_TYPE[row.signal.status as SignalStatus]">{{ SIGNAL_STATUS_LABEL[row.signal.status as SignalStatus] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="收盘" width="85" align="right"><template #default="{ row }">{{ num(row.signal.close) }}</template></el-table-column>
      <el-table-column label="止损（距离）" width="135" align="right">
        <template #default="{ row }">{{ num(row.signal.stop) }}（{{ pct(row.signal.stopDistance, 1) }}）</template>
      </el-table-column>
      <el-table-column label="+1R" width="80" align="right"><template #default="{ row }">{{ num(row.signal.plusOneR) }}</template></el-table-column>
      <el-table-column label="目标 / 盈亏比" width="115" align="right">
        <template #default="{ row }">{{ num(row.signal.target) }} / {{ num(row.signal.rewardRisk) }}</template>
      </el-table-column>
      <el-table-column label="AI" width="60">
        <template #default="{ row }">{{ row.signal.aiStance ? STANCE_LABEL[row.signal.aiStance as keyof typeof STANCE_LABEL] ?? row.signal.aiStance : '—' }}</template>
      </el-table-column>
      <el-table-column label="账本（BASE）" min-width="130">
        <template #default="{ row }">
          <template v-if="row.base">
            {{ TRACK_LABEL[row.base.status as keyof typeof TRACK_LABEL] }}
            <span :class="trend(row.base.rMultiple)">{{ row.base.rMultiple === null ? '' : signedR(row.base.rMultiple) }}</span>
          </template>
          <template v-else>—</template>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link size="small" :disabled="row.signal.status !== 'NEW'" @click.stop="quick(row, 'ACKNOWLEDGED')">已看过</el-button>
          <el-button link size="small" :disabled="row.signal.status !== 'NEW' && row.signal.status !== 'ACKNOWLEDGED'" @click.stop="quick(row, 'DISMISSED')">不做</el-button>
        </template>
      </el-table-column>
    </el-table>

    <SignalDetailDrawer v-model="drawer" :signal-id="pickedId" @changed="load" />
  </div>
</template>

<style scoped>
.tab { display: flex; flex-direction: column; gap: 12px; }
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.muted { color: var(--el-text-color-secondary); font-size: 13px; }
.up { color: #ef5350; }
.down { color: #26a69a; }
</style>
