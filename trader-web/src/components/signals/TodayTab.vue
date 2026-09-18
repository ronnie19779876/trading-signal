<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { signalsApi, type EvaluationRow, type Gate } from '../../api/signals'
import GateDots from './GateDots.vue'
import EvaluationDrawer from './EvaluationDrawer.vue'
import { GATES, GATE_LABEL, OUTCOME_LABEL, ROLE_LABEL, STATUS_LABEL, errMsg, num, pct } from './format'

/** 某天的全部评估：四门漏斗、状态与结果分布、评估表（回答"为什么没信号、卡在哪道门"）。 */
const props = defineProps<{ date: string | null }>()

const rows = ref<EvaluationRow[]>([])
const scope = ref<'pool' | 'all'>('pool')
const outcome = ref<string>('')
const gate = ref<string>('')
const loading = ref(false)
const error = ref<string | null>(null)
const drawer = ref(false)
const picked = ref<EvaluationRow | null>(null)

async function load() {
  if (!props.date) return
  loading.value = true
  error.value = null
  try {
    rows.value = await signalsApi.evaluations({ date: props.date, scope: scope.value })
  } catch (e) {
    error.value = errMsg(e)
    rows.value = []
  } finally {
    loading.value = false
  }
}

watch(() => [props.date, scope.value], load, { immediate: true })

/** 判定成功、有四门结论的行数：漏斗的分母。 */
const evaluated = computed(() => rows.value.filter((r) => r.gates))

/** 漏斗：依次通过前 k 道门的只数（四门不短路，每道门都算了）。 */
const funnel = computed(() =>
  GATES.map((g, k) => {
    const count = evaluated.value.filter((r) => r.gates!.slice(0, k + 1).split('').every((c) => c === 'P')).length
    return {
      gate: g,
      label: GATES.slice(0, k + 1).map((x) => GATE_LABEL[x]).join(' + '),
      count,
      percent: evaluated.value.length ? Math.round((count / evaluated.value.length) * 100) : 0,
    }
  }),
)

const statusCounts = computed(() => count(rows.value.map((r) => STATUS_LABEL[r.status])))
const outcomeCounts = computed(() => count(rows.value.filter((r) => r.outcome).map((r) => OUTCOME_LABEL[r.outcome!])))

function count(values: string[]): [string, number][] {
  const m = new Map<string, number>()
  values.forEach((v) => m.set(v, (m.get(v) ?? 0) + 1))
  return [...m.entries()]
}

const filtered = computed(() =>
  rows.value
    .filter((r) => !outcome.value || r.outcome === outcome.value || r.status === outcome.value)
    .filter((r) => !gate.value || r.firstBlockingGate === (gate.value as Gate))
    .sort((a, b) => b.gatesPassed - a.gatesPassed || a.symbol.localeCompare(b.symbol)),
)

function open(row: EvaluationRow) {
  picked.value = row
  drawer.value = true
}

defineExpose({ load })
</script>

<template>
  <div v-loading="loading" class="tab">
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <div class="toolbar">
      <el-radio-group v-model="scope" size="small">
        <el-radio-button value="pool">池与持仓</el-radio-button>
        <el-radio-button value="all">全部</el-radio-button>
      </el-radio-group>
      <el-select v-model="outcome" size="small" clearable placeholder="结果 / 状态" style="width: 150px">
        <el-option v-for="(label, k) in OUTCOME_LABEL" :key="k" :label="label" :value="k" />
        <el-option v-for="(label, k) in STATUS_LABEL" :key="k" :label="label" :value="k" />
      </el-select>
      <el-select v-model="gate" size="small" clearable placeholder="首个未过的门" style="width: 140px">
        <el-option v-for="g in GATES" :key="g" :label="GATE_LABEL[g]" :value="g" />
      </el-select>
      <span class="muted">{{ date }} · {{ rows.length }} 只 · 点一行看判定过程</span>
    </div>

    <el-row :gutter="12">
      <el-col :span="10">
        <el-card shadow="never" header="四门漏斗（依次通过前几道门）">
          <div v-for="f in funnel" :key="f.gate" class="funnel">
            <span class="funnel__label">{{ f.label }}</span>
            <el-progress :percentage="f.percent" :format="() => String(f.count)" :stroke-width="14" />
          </div>
          <div v-if="!evaluated.length" class="muted">这一天还没有评估结果</div>
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card shadow="never" header="分布">
          <div class="dist">
            <b>状态</b>：<el-tag v-for="[k, v] in statusCounts" :key="k" size="small" type="info">{{ k }} {{ v }}</el-tag>
            <span v-if="!statusCounts.length" class="muted">—</span>
          </div>
          <div class="dist">
            <b>结果</b>：<el-tag v-for="[k, v] in outcomeCounts" :key="k" size="small" :type="k === '信号' ? 'primary' : k === 'AI 否决' ? 'danger' : 'info'">{{ k }} {{ v }}</el-tag>
            <span v-if="!outcomeCounts.length" class="muted">—</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <el-table :data="filtered" size="small" max-height="560" highlight-current-row
                empty-text="这一天没有评估结果：看 SIGNAL_EVALUATION 作业跑了没有" @row-click="open">
        <el-table-column prop="symbol" label="代码" width="90" fixed />
        <el-table-column label="角色" width="60"><template #default="{ row }">{{ ROLE_LABEL[row.role as keyof typeof ROLE_LABEL] }}</template></el-table-column>
        <el-table-column label="结果" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.outcome" size="small" :type="row.outcome === 'SIGNAL' ? 'primary' : row.outcome === 'BLOCKED_BY_AI' ? 'danger' : 'info'">
              {{ OUTCOME_LABEL[row.outcome as keyof typeof OUTCOME_LABEL] }}
            </el-tag>
            <el-tag v-else size="small" type="warning">{{ STATUS_LABEL[row.status as keyof typeof STATUS_LABEL] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="四门" width="90"><template #default="{ row }"><GateDots :gates="row.gates" /></template></el-table-column>
        <el-table-column label="卡在" width="70">
          <template #default="{ row }">{{ row.firstBlockingGate ? GATE_LABEL[row.firstBlockingGate as Gate] : '—' }}</template>
        </el-table-column>
        <el-table-column label="收盘" width="90" align="right"><template #default="{ row }">{{ num(row.close) }}</template></el-table-column>
        <el-table-column label="RVOL" width="70" align="right"><template #default="{ row }">{{ num(row.rvol) }}</template></el-table-column>
        <el-table-column label="止损距离" width="90" align="right"><template #default="{ row }">{{ pct(row.stopDistance) }}</template></el-table-column>
        <el-table-column label="说明" min-width="200"><template #default="{ row }"><span class="muted">{{ row.statusDetail ?? '' }}</span></template></el-table-column>
      </el-table>
    </el-card>

    <EvaluationDrawer v-model="drawer" :symbol="picked?.symbol ?? null" :date="picked?.tradeDate ?? null" />
  </div>
</template>

<style scoped>
.tab { display: flex; flex-direction: column; gap: 12px; }
.toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.funnel { display: grid; grid-template-columns: 170px 1fr; align-items: center; gap: 8px; margin: 6px 0; font-size: 13px; }
.dist { margin: 6px 0; display: flex; gap: 6px; flex-wrap: wrap; align-items: center; font-size: 13px; }
</style>
