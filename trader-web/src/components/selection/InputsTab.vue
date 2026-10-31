<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { selectionApi, type InputsReport } from '../../api/selection'
import type { AuditReport } from '../../api/account'
import { auditCheckLabel } from '../../lib/audit'
import { errMsg } from '../../lib/format'

/**
 * Data and audit: coverage of the scoring inputs (the 6.1 acceptance definition) and every check of the day's audit,
 * including the known-bias text verbatim. Backfill and replay are not done here -- the page is read-only; to change
 * data use the API (see docs/API.md).
 */
const props = defineProps<{ audit: AuditReport | null }>()

const report = ref<InputsReport | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    report.value = await selectionApi.inputs()
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)
defineExpose({ load })

const PART_LABEL: Record<string, string> = {
  TYPES: '证券类型（盈透）', PRICES: '早年价格（盈透）', VALUATION: '估值历史（富途）', EARNINGS: '公告日（富途）', FINANCIALS: '季报（富途）',
}
const STATUS_LABEL: Record<string, string> = { OK: '完成', SKIPPED: '不需要', EMPTY: '券商没有', PENDING: '待重试', FAILED: '失败' }
const PART_ORDER = ['TYPES', 'PRICES', 'VALUATION', 'EARNINGS', 'FINANCIALS']
const parts = computed(() => PART_ORDER.filter((p) => report.value?.parts[p]).map((p) => ({ part: p, ...report.value!.parts[p] })))
const checks = computed(() => (props.audit?.checks ?? []).filter((c) => c.name !== 'knownBiases'))
const biases = computed(() => props.audit?.checks.find((c) => c.name === 'knownBiases')?.samples ?? [])
const r = computed(() => report.value)
const pctOf = (a: number, b: number) => (b ? `${((a / b) * 100).toFixed(1)}%` : '—')
</script>

<template>
  <div v-loading="loading" class="tab">
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />

    <section v-if="audit" class="panel">
      <div class="panel__head">
        <h3>{{ audit.date }} 审计</h3>
        <el-tag size="small" :type="audit.ok ? 'success' : 'danger'" effect="plain">{{ audit.ok ? '通过' : '不通过' }}</el-tag>
      </div>
      <table class="dtable">
        <tbody>
          <tr v-for="c in checks" :key="c.name">
            <td style="width: 28px">{{ c.ok ? '✅' : c.critical ? '❌' : '⚠️' }}</td>
            <td style="width: 120px"><b>{{ auditCheckLabel(c.name) }}</b><span v-if="c.critical" class="muted">（关键）</span></td>
            <td class="wrap">{{ c.detail }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <template v-if="r">
      <section class="panel">
        <div class="panel__head">
          <h3>输入覆盖</h3>
          <span class="panel__src">范围 {{ r.targets }} 只普通股 · 截至 {{ r.asOf }}</span>
        </div>
        <table class="dtable">
          <thead><tr><th>部分</th><th>各状态只数</th><th class="r">没取过</th></tr></thead>
          <tbody>
            <tr v-for="p in parts" :key="p.part">
              <td><b>{{ PART_LABEL[p.part] ?? p.part }}</b></td>
              <td>
                <span v-for="(n, st) in p.byStatus" :key="st" class="status" :class="`status--${String(st).toLowerCase()}`">
                  {{ STATUS_LABEL[st] ?? st }} <b class="num">{{ n }}</b>
                </span>
              </td>
              <td class="r num" :class="{ warn: p.missing > 0 }">{{ p.missing }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <div class="kv-grid">
        <div class="kv"><label>盈透接缝通过</label><b class="num">{{ r.seams.ok }} / {{ r.seams.checked }}</b></div>
        <div class="kv"><label>盈透日线</label><b class="num">{{ r.priceBars.rows.toLocaleString() }} 根 · {{ r.priceBars.instruments }} 只</b></div>
        <div class="kv"><label>估值历史</label><b class="num">{{ r.valuation.rows.toLocaleString() }} 天 · 自 {{ r.valuation.earliest ?? '—' }}</b></div>
        <div class="kv"><label>估值与快照同日不一致（PE / PB）</label>
          <b class="num">{{ r.valuationVsSnapshot.peMismatch }} / {{ r.valuationVsSnapshot.pbMismatch }}（比对 {{ r.valuationVsSnapshot.compared.toLocaleString() }}）</b></div>
        <div class="kv"><label>季报回到 2016 年一季度之前</label><b class="num">{{ r.financials.deep }} / {{ r.financials.instruments }}</b></div>
        <div class="kv"><label>近 4 年季报有公告日（期末后 75 天内）</label>
          <b class="num">{{ r.earnings.matched.toLocaleString() }} / {{ r.earnings.reports.toLocaleString() }}（{{ pctOf(r.earnings.matched, r.earnings.reports) }}）</b></div>
        <div class="kv"><label>公告晚于法定期限</label><b class="num">{{ r.earnings.beyondRule }} 期</b></div>
        <div class="kv"><label>期末到公告中位数</label><b class="num">{{ r.earnings.medianLagDays ?? '—' }} 天</b></div>
      </div>
      <p class="muted note">
        没有公告日的季度按兜底规则给可用日：max(法定期限 40 / 60 天, 该标的已观测最大滞后)。
        季报不够深的多是 2016 年后才上市或分拆的：{{ r.financials.shallowSamples.slice(0, 12).join('、') }}{{ r.financials.shallowSamples.length > 12 ? ' …' : '' }}
      </p>

      <section v-if="r.failed.length || r.empty.length" class="panel">
        <div class="panel__head"><h3>失败与券商没有的</h3></div>
        <table class="dtable">
          <thead><tr><th>代码</th><th>部分</th><th>状态</th><th>说明</th></tr></thead>
          <tbody>
            <tr v-for="row in [...r.failed, ...r.empty]" :key="`${row.instrumentId}-${row.part}`">
              <td><b>{{ row.symbol }}</b></td>
              <td>{{ PART_LABEL[row.part] ?? row.part }}</td>
              <td>{{ STATUS_LABEL[row.status] ?? row.status }}</td>
              <td class="muted wrap">{{ row.detail }}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </template>

    <section v-if="biases.length" class="panel">
      <div class="panel__head"><h3>已知偏差</h3><span class="panel__src">回测报告必须原样带出（ARCHITECTURE §25.8）</span></div>
      <ol class="biases"><li v-for="b in biases" :key="b">{{ b }}</li></ol>
    </section>
  </div>
</template>

<style scoped>
.tab { display: flex; flex-direction: column; gap: 12px; }
.dtable { font-size: 13px; }
.wrap { white-space: normal; word-break: break-all; }
.status { display: inline-block; margin-right: 12px; }
.status--failed b { color: var(--el-color-danger); }
.status--pending b { color: var(--el-color-warning); }
.warn { color: var(--el-color-warning); }
.note { font-size: 12px; line-height: 1.7; margin: 0; }
.biases { margin: 0; padding-left: 20px; font-size: 13px; line-height: 1.8; }
</style>
