<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { signalsApi, type SentinelEvaluation, type SignalDetail } from '../../api/signals'
import { aiApi, type AiAnalysis } from '../../api/ai'
import JudgementView from './JudgementView.vue'
import SignalChart from './SignalChart.vue'
import AiOpinion from './AiOpinion.vue'
import {
  EXIT_LABEL, ROLE_LABEL, SIGNAL_STATUS_LABEL, SIGNAL_STATUS_TYPE, TRACK_LABEL, errMsg, iso, num, pct, signedR, trend,
} from './format'

/** 一条信号的全部信息：价位与图、四门判据、指纹核对、AI 第二意见、两个出场变体的纸面账本。 */
const props = defineProps<{ signalId: number | null }>()
const visible = defineModel<boolean>({ default: false })
const emit = defineEmits<{ changed: [] }>()

const detail = ref<SignalDetail | null>(null)
const ai = ref<AiAnalysis | null>(null)
const loading = ref(false)
const analyzing = ref(false)
const error = ref<string | null>(null)

const s = computed(() => detail.value?.signal ?? null)
const evaluation = computed<SentinelEvaluation | null>(
  () => detail.value?.evaluation?.detail?.evaluation ?? detail.value?.recomputed?.evaluation ?? null,
)
const baseTrack = computed(() => detail.value?.tracks.find((t) => t.variant === 'BASE') ?? null)

async function load() {
  if (props.signalId === null) return
  loading.value = true
  error.value = null
  ai.value = null
  try {
    detail.value = await signalsApi.detail(props.signalId)
    const sig = detail.value.signal
    if (sig.aiAnalysisId) {
      ai.value = await aiApi.find(sig.aiAnalysisId)
    } else {
      // 手工分析不挂在信号上：找同一只同一判定日最近一次成功的
      const list = await aiApi.list({ symbol: sig.symbol, from: sig.tradeDate, to: iso(new Date()), limit: 50 })
      ai.value = list.find((a) => a.tradeDate === sig.tradeDate && a.status === 'OK') ?? null
    }
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    loading.value = false
  }
}

watch(() => [props.signalId, visible.value], () => { if (visible.value) load() }, { immediate: true })

async function changeStatus(to: 'ACKNOWLEDGED' | 'DISMISSED') {
  if (!s.value) return
  let note = ''
  try {
    const r = await ElMessageBox.prompt(
      `把 ${s.value.symbol} ${s.value.tradeDate} 的信号标为「${SIGNAL_STATUS_LABEL[to]}」？可以写一句备注。`,
      '改信号状态',
      { confirmButtonText: '确定', cancelButtonText: '取消', inputPlaceholder: '备注（可空）' },
    )
    note = (r as { value: string }).value ?? ''
  } catch {
    return
  }
  try {
    await signalsApi.changeStatus(s.value.id, to, note || undefined)
    ElMessage.success('已更新')
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(errMsg(e))
  }
}

async function analyze() {
  if (!s.value) return
  try {
    await ElMessageBox.confirm(
      `对 ${s.value.symbol} ${s.value.tradeDate} 调用模型做第二意见：会计费，约 15~30 秒，计入每日上限；同一输入已有结论会直接复用。`,
      '手工分析',
      { type: 'warning', confirmButtonText: '调用', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  analyzing.value = true
  try {
    ai.value = await aiApi.analyze(s.value.symbol, s.value.tradeDate)
    if (ai.value.status !== 'OK') ElMessage.warning(`没有结论：${ai.value.error ?? ai.value.status}`)
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    analyzing.value = false
  }
}
</script>

<template>
  <el-drawer v-model="visible" size="70%" :title="s ? `${s.symbol} · ${s.tradeDate} 入场信号` : '入场信号'" destroy-on-close>
    <div v-loading="loading" class="drawer">
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
      <template v-if="s && detail">
        <div class="head">
          <el-tag :type="SIGNAL_STATUS_TYPE[s.status]" effect="dark">{{ SIGNAL_STATUS_LABEL[s.status] }}</el-tag>
          <el-tag type="info">{{ ROLE_LABEL[s.role] }}</el-tag>
          <el-tag :type="s.origin === 'LIVE' ? 'success' : 'info'">{{ s.origin === 'LIVE' ? '实盘' : '补跑' }}</el-tag>
          <span class="muted">有效至 {{ s.expiresOn }} 收盘 · {{ s.rulesetVersion }}</span>
          <span v-if="s.note" class="muted">备注：{{ s.note }}</span>
          <span class="spacer"></span>
          <el-button size="small" :disabled="s.status !== 'NEW'" @click="changeStatus('ACKNOWLEDGED')">已看过</el-button>
          <el-button size="small" :disabled="s.status !== 'NEW' && s.status !== 'ACKNOWLEDGED'" @click="changeStatus('DISMISSED')">不做</el-button>
        </div>

        <el-descriptions :column="4" size="small" border>
          <el-descriptions-item label="判定日收盘">{{ num(s.close) }}</el-descriptions-item>
          <el-descriptions-item label="止损">{{ num(s.stop) }}（{{ s.stopLeg === 'ZONE' ? '区底腿' : 'ATR 腿' }}，{{ pct(s.stopDistance) }}）</el-descriptions-item>
          <el-descriptions-item label="R / +1R">{{ num(s.riskPerShare) }} / {{ num(s.plusOneR) }}</el-descriptions-item>
          <el-descriptions-item label="ATR14">{{ num(s.atr14) }}</el-descriptions-item>
          <el-descriptions-item label="吊灯止损">{{ num(s.chandelierStop) }}</el-descriptions-item>
          <el-descriptions-item label="参考目标">{{ num(s.target) }}（盈亏比 {{ num(s.rewardRisk) }}）</el-descriptions-item>
          <el-descriptions-item label="支撑区">
            {{ s.zoneBottom === null ? '—' : `[${num(s.zoneBottom)}, ${num(s.zoneTop)}] × ${s.zoneTouches}` }}
          </el-descriptions-item>
          <el-descriptions-item label="加分项">
            斐波那契 {{ s.bonus?.fibConfluence ? '重合' : '—' }} · MACD {{ s.bonus?.macdPositive ? '柱为正' : '—' }}
          </el-descriptions-item>
        </el-descriptions>

        <el-card shadow="never">
          <SignalChart :symbol="s.symbol" :as-of="s.tradeDate" :stop="s.stop" :plus-one-r="s.plusOneR" :chandelier="s.chandelierStop"
                       :target="s.target" :zone="s.zoneBottom !== null && s.zoneTop !== null ? { bottom: s.zoneBottom, top: s.zoneTop } : null"
                       :track="baseTrack" />
        </el-card>

        <el-card shadow="never" header="AI 第二意见">
          <AiOpinion v-if="ai" :analysis="ai" />
          <div v-else class="empty">
            <span class="muted">
              没有模型结论（补跑不调模型；实盘信号在评估作业里调用，未开启、失败或预算用尽时也没有）。
            </span>
            <el-button size="small" type="warning" plain :loading="analyzing" @click="analyze">手工分析（计费）</el-button>
          </div>
        </el-card>

        <el-card shadow="never" header="四门判定">
          <el-alert v-if="detail.evaluation && !detail.fingerprintMatches" type="warning" :closable="false" show-icon
                    title="输入指纹与当前数据不一致：判定后 K 线或复权因子被重拉改过，下方判据按判定时存档展示" />
          <JudgementView v-if="evaluation" :evaluation="evaluation" />
        </el-card>

        <el-card shadow="never" header="纸面账本（次日开盘入场；BASE 止损 2.0×ATR，STOP_2_5 止损 2.5×ATR；都不减半仓）">
          <el-table :data="detail.tracks" size="small">
            <el-table-column prop="variant" label="变体" width="100" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">{{ TRACK_LABEL[row.status as keyof typeof TRACK_LABEL] }}</template>
            </el-table-column>
            <el-table-column label="止损" width="90" align="right"><template #default="{ row }">{{ num(row.stop) }}</template></el-table-column>
            <el-table-column label="入场" width="150"><template #default="{ row }">{{ row.entryDate ?? '—' }} {{ num(row.entryPrice) }}</template></el-table-column>
            <el-table-column label="离场" width="200">
              <template #default="{ row }">
                <template v-if="row.exitDate">{{ row.exitDate }} {{ num(row.exitPrice) }}（{{ EXIT_LABEL[row.exitReason as keyof typeof EXIT_LABEL] }}）</template>
                <template v-else>—</template>
              </template>
            </el-table-column>
            <el-table-column label="R" width="80" align="right">
              <template #default="{ row }"><span :class="trend(row.rMultiple)">{{ signedR(row.rMultiple) }}</span></template>
            </el-table-column>
            <el-table-column label="收益" width="80" align="right">
              <template #default="{ row }"><span :class="trend(row.returnPct)">{{ pct(row.returnPct) }}</span></template>
            </el-table-column>
            <el-table-column label="最大浮盈 / 浮亏" min-width="130">
              <template #default="{ row }">{{ signedR(row.mfeR) }} / {{ signedR(row.maeR) }}</template>
            </el-table-column>
            <el-table-column label="算到" width="100"><template #default="{ row }">{{ row.updatedThrough ?? '—' }}</template></el-table-column>
          </el-table>
        </el-card>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer { display: flex; flex-direction: column; gap: 12px; }
.head { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.spacer { flex: 1; }
.empty { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.muted { color: var(--el-text-color-secondary); font-size: 13px; }
.up { color: #ef5350; }
.down { color: #26a69a; }
</style>
