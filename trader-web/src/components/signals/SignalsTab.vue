<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { signalsApi, type SignalStatus, type SignalView } from '../../api/signals'
import SignalDetailDrawer from './SignalDetailDrawer.vue'
import type { InstrumentView } from '../../api/marketdata'
import { usePager } from '../../composables/usePager'
import {
  ROLE_LABEL, SIGNAL_STATUS_LABEL, SIGNAL_STATUS_TYPE, STANCE_LABEL, TRACK_LABEL, daysAgo, errMsg, iso, num, pct, signedR, trend,
} from './format'

/** 信号列表：状态、来源、范围过滤，分页；已看过 / 不做；点一行看详情。 */
const props = defineProps<{ universe: InstrumentView[] }>()
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

const names = computed(() => new Map(props.universe.map((u) => [u.symbol, u.nameCn ?? u.name ?? null])))
const { page, pageSize, paged, total } = usePager(rows)

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
      <span class="muted">{{ rows.length }} 条 · 价位为判定日口径 · 点一行看详情</span>
    </div>

    <section class="panel">
      <div class="dtable-wrap">
        <table class="dtable">
          <thead>
            <tr>
              <th>判定日</th><th>代码</th><th>角色</th><th>来源</th><th>状态</th><th class="r">收盘</th><th class="r">止损（距离）</th>
              <th class="r">+1R</th><th class="r">目标 · 盈亏比</th><th>AI</th><th>账本（BASE）</th><th class="r">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in paged" :key="row.signal.id" class="clickable" @click="open(row)">
              <td>{{ row.signal.tradeDate }}</td>
              <td><b>{{ row.signal.symbol }}</b><span v-if="names.get(row.signal.symbol)" class="name">{{ names.get(row.signal.symbol) }}</span></td>
              <td>{{ ROLE_LABEL[row.signal.role] }}</td>
              <td>{{ row.signal.origin === 'LIVE' ? '实盘' : '补跑' }}</td>
              <td><el-tag size="small" :type="SIGNAL_STATUS_TYPE[row.signal.status as SignalStatus]">{{ SIGNAL_STATUS_LABEL[row.signal.status as SignalStatus] }}</el-tag></td>
              <td class="r num">{{ num(row.signal.close) }}</td>
              <td class="r num">{{ num(row.signal.stop) }}<span class="muted-cell">（{{ pct(row.signal.stopDistance, 1) }}）</span></td>
              <td class="r num">{{ num(row.signal.plusOneR) }}</td>
              <td class="r num">{{ num(row.signal.target) }} · {{ num(row.signal.rewardRisk) }}</td>
              <td>{{ row.signal.aiStance ? STANCE_LABEL[row.signal.aiStance as keyof typeof STANCE_LABEL] ?? row.signal.aiStance : '—' }}</td>
              <td>
                <template v-if="row.base">
                  {{ TRACK_LABEL[row.base.status] }}
                  <span :class="trend(row.base.rMultiple)">{{ row.base.rMultiple === null ? '' : signedR(row.base.rMultiple) }}</span>
                </template>
                <template v-else>—</template>
              </td>
              <td class="r">
                <el-button link size="small" :disabled="row.signal.status !== 'NEW'" @click.stop="quick(row, 'ACKNOWLEDGED')">已看过</el-button>
                <el-button link size="small" :disabled="row.signal.status !== 'NEW' && row.signal.status !== 'ACKNOWLEDGED'" @click.stop="quick(row, 'DISMISSED')">不做</el-button>
              </td>
            </tr>
            <tr v-if="!rows.length">
              <td colspan="12" class="empty">{{ loading ? '加载中…' : '这个范围内没有信号' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="total > pageSize" class="pager">
        <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="total" :page-sizes="[20, 50, 100]"
                       layout="total, sizes, prev, pager, next, jumper" size="small" background />
      </div>
    </section>

    <SignalDetailDrawer v-model="drawer" :signal-id="pickedId" @changed="load" />
  </div>
</template>

<style scoped>
.tab { display: flex; flex-direction: column; gap: 12px; }
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.dtable { font-size: 13px; }
.name { margin-left: 8px; font-size: 12px; color: var(--el-text-color-secondary); }
.muted-cell { color: var(--el-text-color-secondary); }
.pager { display: flex; justify-content: flex-end; margin-top: 10px; }
</style>
