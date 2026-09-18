<script setup lang="ts">
import { computed } from 'vue'
import type { SentinelEvaluation } from '../../api/signals'
import { GATE_LABEL, STATUS_LABEL, num } from './format'

/** 一次四门判定的展开：判据原文、支撑区、出场预案、加分项。 */
const props = defineProps<{ evaluation: SentinelEvaluation }>()

const e = computed(() => props.evaluation)
const VERDICT_TYPE = { PASS: 'success', FAIL: 'info', UNAVAILABLE: 'warning' } as const
const VERDICT_LABEL = { PASS: '通过', FAIL: '不过', UNAVAILABLE: '不可判定' } as const
</script>

<template>
  <div class="judgement">
    <el-alert v-if="e.status !== 'EVALUATED'" type="warning" :closable="false" show-icon
              :title="`${STATUS_LABEL[e.status]}：${e.statusDetail ?? ''}`" />
    <template v-else>
      <el-table :data="e.gates" size="small">
        <el-table-column label="门" width="70">
          <template #default="{ row }">{{ GATE_LABEL[row.gate as keyof typeof GATE_LABEL] }}</template>
        </el-table-column>
        <el-table-column label="结论" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="VERDICT_TYPE[row.verdict as keyof typeof VERDICT_TYPE]">
              {{ VERDICT_LABEL[row.verdict as keyof typeof VERDICT_LABEL] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="criteria" label="判据（代入值）" min-width="360" />
      </el-table>

      <div class="facts">
        <div v-if="e.exitPlan">
          <b>出场预案</b>：初始止损 {{ num(e.exitPlan.initialStop) }} · R {{ num(e.exitPlan.riskPerShare) }} ·
          +1R {{ num(e.exitPlan.plusOneR) }} · 吊灯止损 {{ num(e.exitPlan.chandelierStop) }}（触及 +1R 后生效）·
          时间止损 {{ e.exitPlan.timeStopDays }} 个交易日 ·
          参考目标 {{ num(e.exitPlan.target) }}（盈亏比 {{ num(e.exitPlan.rewardRisk) }}）
        </div>
        <div>
          <b>加分项</b>：斐波那契重合 {{ e.bonus.fibConfluence ? '是' : '否' }} · MACD 柱为正 {{ e.bonus.macdPositive ? '是' : '否' }}
        </div>
        <div>
          <b>支撑区</b>（回看 252 根内，触及 ≥ 2 次）：
          <span v-if="!e.zones.length" class="muted">无</span>
          <el-tag v-for="z in e.zones" :key="z.bottom" size="small" :type="e.hitZone && z.bottom === e.hitZone.bottom ? 'primary' : 'info'"
                  class="zone">
            [{{ num(z.bottom) }}, {{ num(z.top) }}] × {{ z.touches }}
          </el-tag>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.judgement { display: flex; flex-direction: column; gap: 8px; }
.facts { font-size: 13px; line-height: 1.8; color: var(--el-text-color-regular); }
.zone { margin: 2px 4px 2px 0; }
</style>
