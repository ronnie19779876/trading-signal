<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { signalsApi, type Ledger, type SignalTrack } from '../../api/signals'
import SignalDetailDrawer from './SignalDetailDrawer.vue'
import { EXIT_LABEL, ROLE_LABEL, TRACK_LABEL, errMsg, num, pct, signedR, trend } from './format'

/** 纸面账本：按变体 × 来源 × AI 裁决分组的汇总、两个变体的配对差、明细。 */
const ledger = ref<Ledger | null>(null)
const variant = ref<string>('BASE')
const status = ref<string>('')
const loading = ref(false)
const error = ref<string | null>(null)
const drawer = ref(false)
const pickedId = ref<number | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    ledger.value = await signalsApi.ledger({ variant: variant.value, status: status.value })
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    loading.value = false
  }
}

watch([variant, status], load, { immediate: true })

const AI_LABEL = { VETO: 'AI 否决', ALLOW: 'AI 放行', NONE: '无 AI 结论' } as const
const entries = computed(() => ledger.value?.entries ?? [])

function open(row: { signal: { id: number }; track: SignalTrack }) {
  pickedId.value = row.signal.id
  drawer.value = true
}

defineExpose({ load })
</script>

<template>
  <div v-loading="loading" class="tab">
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <el-alert type="info" :closable="false" show-icon
              title="补跑与实盘分开看；样本少、集中在同一段行情时不能据此下结论（历史回放显示入场判据本身没有择时价值，见 ARCHITECTURE §18.6）" />

    <el-card shadow="never" header="汇总（已平仓才计入胜率与期望）">
      <el-table :data="ledger?.stats ?? []" size="small">
        <el-table-column prop="variant" label="变体" width="100" />
        <el-table-column label="来源" width="70"><template #default="{ row }">{{ row.origin === 'LIVE' ? '实盘' : '补跑' }}</template></el-table-column>
        <el-table-column label="AI" width="100"><template #default="{ row }">{{ AI_LABEL[row.ai as keyof typeof AI_LABEL] }}</template></el-table-column>
        <el-table-column prop="total" label="总数" width="70" align="right" />
        <el-table-column prop="pending" label="待入场" width="70" align="right" />
        <el-table-column prop="open" label="持有中" width="70" align="right" />
        <el-table-column prop="closed" label="已平仓" width="70" align="right" />
        <el-table-column label="胜率" width="80" align="right"><template #default="{ row }">{{ pct(row.winRate, 1) }}</template></el-table-column>
        <el-table-column label="每笔 R" width="90" align="right">
          <template #default="{ row }"><span :class="trend(row.meanR)">{{ signedR(row.meanR) }}</span></template>
        </el-table-column>
        <el-table-column label="每笔收益" min-width="90" align="right">
          <template #default="{ row }"><span :class="trend(row.meanReturn)">{{ pct(row.meanReturn) }}</span></template>
        </el-table-column>
      </el-table>
      <div class="muted paired">
        配对（同一批已平仓信号）：STOP_2_5 − BASE 每笔收益差
        <b :class="trend(ledger?.pairedMeanReturnDiff)">{{ pct(ledger?.pairedMeanReturnDiff) }}</b>，{{ ledger?.pairedCount ?? 0 }} 笔
      </div>
    </el-card>

    <el-card shadow="never">
      <div class="toolbar">
        <el-radio-group v-model="variant" size="small">
          <el-radio-button value="BASE">BASE</el-radio-button>
          <el-radio-button value="STOP_2_5">STOP_2_5</el-radio-button>
        </el-radio-group>
        <el-select v-model="status" size="small" clearable placeholder="状态" style="width: 110px">
          <el-option v-for="(label, k) in TRACK_LABEL" :key="k" :label="label" :value="k" />
        </el-select>
        <span class="muted">{{ entries.length }} 条 · 点一行看信号详情</span>
      </div>
      <el-table :data="entries" size="small" max-height="560" @row-click="open">
        <el-table-column label="判定日" width="100"><template #default="{ row }">{{ row.signal.tradeDate }}</template></el-table-column>
        <el-table-column label="代码" width="80"><template #default="{ row }"><b>{{ row.signal.symbol }}</b></template></el-table-column>
        <el-table-column label="角色" width="60"><template #default="{ row }">{{ ROLE_LABEL[row.signal.role as keyof typeof ROLE_LABEL] }}</template></el-table-column>
        <el-table-column label="状态" width="80"><template #default="{ row }">{{ TRACK_LABEL[row.track.status as keyof typeof TRACK_LABEL] }}</template></el-table-column>
        <el-table-column label="入场" width="160"><template #default="{ row }">{{ row.track.entryDate ?? '—' }} {{ num(row.track.entryPrice) }}</template></el-table-column>
        <el-table-column label="止损" width="85" align="right"><template #default="{ row }">{{ num(row.track.stop) }}</template></el-table-column>
        <el-table-column label="离场" width="210">
          <template #default="{ row }">
            <template v-if="row.track.exitDate">{{ row.track.exitDate }} {{ num(row.track.exitPrice) }}（{{ EXIT_LABEL[row.track.exitReason as keyof typeof EXIT_LABEL] }}）</template>
            <template v-else>—</template>
          </template>
        </el-table-column>
        <el-table-column label="R" width="80" align="right"><template #default="{ row }"><span :class="trend(row.track.rMultiple)">{{ signedR(row.track.rMultiple) }}</span></template></el-table-column>
        <el-table-column label="收益" width="80" align="right"><template #default="{ row }"><span :class="trend(row.track.returnPct)">{{ pct(row.track.returnPct) }}</span></template></el-table-column>
        <el-table-column label="持有" min-width="60" align="right"><template #default="{ row }">{{ row.track.barsHeld ?? '—' }}</template></el-table-column>
      </el-table>
    </el-card>

    <SignalDetailDrawer v-model="drawer" :signal-id="pickedId" @changed="load" />
  </div>
</template>

<style scoped>
.tab { display: flex; flex-direction: column; gap: 12px; }
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 8px; }
.paired { margin-top: 8px; }
</style>
