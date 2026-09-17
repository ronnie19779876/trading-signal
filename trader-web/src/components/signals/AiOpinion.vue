<script setup lang="ts">
import { computed } from 'vue'
import type { AiAnalysis, EvidenceCheck } from '../../api/ai'
import { AI_STATUS_LABEL, CONFIDENCE_LABEL, STANCE_LABEL, STANCE_TYPE, VERDICT_LABEL } from './format'

/** 模型第二意见：立场、结论、多空证据左右两栏（核对不通过的标黄）、风险与数据缺口、裁决依据、用量。 */
const props = defineProps<{ analysis: AiAnalysis }>()

const a = computed(() => props.analysis)
const j = computed(() => a.value.judgment)

function checked(side: 'BULL' | 'BEAR', index: number): EvidenceCheck | undefined {
  return (a.value.checks ?? []).filter((c) => c.side === side)[index]
}

const VERDICT_TYPE = { VETO: 'danger', ALLOW: 'success', ABSENT: 'info' } as const
</script>

<template>
  <div class="ai">
    <div class="ai__head">
      <el-tag :type="VERDICT_TYPE[a.verdict]" effect="dark">{{ VERDICT_LABEL[a.verdict] }}</el-tag>
      <template v-if="j">
        <el-tag :type="STANCE_TYPE[j.stance]">{{ STANCE_LABEL[j.stance] }}</el-tag>
        <el-tag type="info">{{ CONFIDENCE_LABEL[j.confidence] }}</el-tag>
      </template>
      <el-tag v-if="a.status !== 'OK'" type="warning">{{ AI_STATUS_LABEL[a.status] }}</el-tag>
      <span class="muted">{{ a.purpose === 'MANUAL' ? '手工分析' : '评估作业' }} · #{{ a.id }} · {{ new Date(a.createdAt).toLocaleString('zh-CN', { hour12: false }) }}</span>
    </div>
    <div v-if="a.verdictReason" class="ai__reason">裁决依据：{{ a.verdictReason }}</div>
    <el-alert v-if="a.error" :title="a.error" type="warning" :closable="false" />

    <template v-if="j">
      <div class="ai__summary">{{ j.summary }}</div>
      <el-row :gutter="12">
        <el-col :span="12">
          <div class="ai__col ai__col--bull">
            <div class="ai__col-title">看多证据</div>
            <div v-if="!j.bullEvidence.length" class="muted">无</div>
            <div v-for="(ev, i) in j.bullEvidence" :key="'b' + i" class="ev" :class="{ 'ev--bad': checked('BULL', i) && !checked('BULL', i)!.verified }">
              <div>{{ ev.point }}</div>
              <div class="ev__src">{{ ev.field }} = {{ ev.value }}
                <span v-if="checked('BULL', i) && !checked('BULL', i)!.verified">（核对不通过：{{ checked('BULL', i)!.reason }}）</span>
              </div>
            </div>
          </div>
        </el-col>
        <el-col :span="12">
          <div class="ai__col ai__col--bear">
            <div class="ai__col-title">看空证据</div>
            <div v-if="!j.bearEvidence.length" class="muted">无</div>
            <div v-for="(ev, i) in j.bearEvidence" :key="'s' + i" class="ev" :class="{ 'ev--bad': checked('BEAR', i) && !checked('BEAR', i)!.verified }">
              <div>{{ ev.point }}</div>
              <div class="ev__src">{{ ev.field }} = {{ ev.value }}
                <span v-if="checked('BEAR', i) && !checked('BEAR', i)!.verified">（核对不通过：{{ checked('BEAR', i)!.reason }}）</span>
              </div>
            </div>
          </div>
        </el-col>
      </el-row>
      <div v-if="j.vetoReason" class="ai__line"><b>否决理由</b>：{{ j.vetoReason }}</div>
      <div v-if="j.risks.length" class="ai__line"><b>风险</b>：{{ j.risks.join('；') }}</div>
      <div v-if="j.dataGaps.length" class="ai__line muted"><b>数据缺口</b>：{{ j.dataGaps.join('；') }}</div>
    </template>

    <div class="muted ai__usage">
      {{ a.model }} · {{ a.reasoningEffort }} · {{ a.promptVersion }} ·
      输入 {{ a.inputTokens ?? '—' }}（缓存 {{ a.cachedTokens ?? '—' }}）· 输出 {{ a.outputTokens ?? '—' }}（推理 {{ a.reasoningTokens ?? '—' }}）·
      {{ a.latencyMs ? (a.latencyMs / 1000).toFixed(1) + ' 秒' : '—' }}
    </div>
  </div>
</template>

<style scoped>
.ai { display: flex; flex-direction: column; gap: 8px; font-size: 13px; }
.ai__head { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
.ai__reason { color: var(--el-text-color-regular); }
.ai__summary { font-size: 14px; }
.ai__col { border-radius: 4px; padding: 8px; min-height: 60px; }
.ai__col--bull { background: #fef0f0; }
.ai__col--bear { background: #f0f9eb; }
.ai__col-title { font-weight: 600; margin-bottom: 4px; }
.ev { padding: 4px 0; border-bottom: 1px dashed #e4e7ed; }
.ev:last-child { border-bottom: none; }
.ev--bad { background: #fdf6ec; }
.ev__src { color: var(--el-text-color-secondary); font-size: 12px; word-break: break-all; }
.ai__line { line-height: 1.7; }
.ai__usage { font-size: 12px; }
.muted { color: var(--el-text-color-secondary); }
</style>
