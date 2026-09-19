<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { fundamentalsApi, type FinancialItem, type FinancialReport, type StatementKind } from '../../api/fundamentals'
import { big, errMsg, negative, numMax, trend } from '../../lib/format'

/**
 * 财报：营收与归母净利润柱状图 + 四类报表明细。全是券商原值，同比也是券商给的。
 *
 * 季报与年报分开看：年报与四季报期末是同一天，混在一起读不出趋势。
 * 字段随行业模板不同（一般企业、银行、保险各一套编号），所以表格的行由数据决定；
 * 柱状图按名称取"总收入""归属于母公司股东净利润"，三套模板里都有。
 */
const props = defineProps<{ symbol: string }>()

const STATEMENTS: { value: StatementKind; label: string }[] = [
  { value: 'main_index', label: '主要指标' },
  { value: 'income', label: '利润表' },
  { value: 'balance_sheet', label: '资产负债表' },
  { value: 'cash_flow', label: '现金流量表' },
]
type Period = 'Q' | 'FY'
const statement = ref<StatementKind>('main_index')
const period = ref<Period>('Q')
const PERIODS = 8

const reports = ref<FinancialReport[]>([])
const income = ref<FinancialReport[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

/** 年报每年一期，要取够 8 期得多拿些（接口上限 50）。 */
const LIMIT = 40

async function load() {
  loading.value = true
  error.value = null
  try {
    const [r, inc] = await Promise.all([
      fundamentalsApi.reports(props.symbol, statement.value, LIMIT),
      statement.value === 'income' ? Promise.resolve(null) : fundamentalsApi.reports(props.symbol, 'income', LIMIT),
    ])
    reports.value = r
    income.value = inc ?? r
  } catch (e) {
    reports.value = []
    income.value = []
    error.value = errMsg(e)
  } finally {
    loading.value = false
  }
}
watch([() => props.symbol, statement], load, { immediate: true })

const isFy = (r: FinancialReport) => r.periodText.endsWith('FY')
function pick(list: FinancialReport[]) {
  return list.filter((r) => (period.value === 'FY' ? isFy(r) : !isFy(r))).slice(0, PERIODS)
}
const shown = computed(() => pick(reports.value))

/** 一行一个字段；整行都没有值的是分组标题（如"盈利能力TTM"）。 */
const grid = computed(() => {
  const names = new Map<number, string>()
  for (const r of shown.value) for (const i of r.items) if (i.name) names.set(i.fieldId, i.name)
  return [...names.keys()].sort((a, b) => a - b).map((id) => {
    const cells = shown.value.map((r) => r.items.find((i) => i.fieldId === id) ?? null)
    return { id, name: names.get(id)!, cells, group: cells.every((c) => c?.value === null || c?.value === undefined) }
  })
})

/** 主要指标是比率，原样两位小数；其余报表是金额，大数按亿（每股收益这类小数原样）。 */
function fmt(c: FinancialItem | null): string {
  if (!c || c.value === null) return '—'
  if (statement.value !== 'main_index' && Math.abs(c.value) >= 1e6) return big(c.value)
  return numMax(c.value)
}
function yoy(c: FinancialItem | null): string | null {
  if (!c || c.yoy === null || c.yoy === undefined) return null
  return (c.yoy > 0 ? '+' : '') + c.yoy.toFixed(1) + '%'
}

// ---- 营收与归母净利润（从旧到新） ----
function valueOf(r: FinancialReport, name: string): number | null {
  return r.items.find((i) => i.name === name)?.value ?? null
}
const bars = computed(() =>
  pick(income.value)
    .map((r) => ({ period: r.periodText, revenue: valueOf(r, '总收入'), profit: valueOf(r, '归属于母公司股东净利润') }))
    .reverse(),
)
const barMax = computed(() => Math.max(1, ...bars.value.flatMap((b) => [Math.abs(b.revenue ?? 0), Math.abs(b.profit ?? 0)])))
const pctOf = (v: number | null) => (v === null ? 0 : (Math.abs(v) / barMax.value) * 100)
const currency = computed(() => reports.value[0]?.currency ?? null)
</script>

<template>
  <section v-loading="loading" class="panel">
    <div class="panel__head">
      <h3>财报</h3>
      <span class="panel__src">券商原值；每格下方小字是券商给的同比{{ currency ? ' · 币种 ' + currency : '' }}</span>
      <span class="panel__grow" />
      <el-radio-group v-model="period" size="small">
        <el-radio-button value="Q">季报</el-radio-button>
        <el-radio-button value="FY">年报</el-radio-button>
      </el-radio-group>
    </div>
    <el-alert v-if="error" :title="'财报取数失败：' + error" type="error" :closable="false" />

    <div v-if="bars.some((b) => b.revenue !== null)" class="bars">
      <div class="bars__legend">
        <span><i class="sw sw--rev" />总收入</span><span><i class="sw sw--np" />归母净利润</span>
      </div>
      <div class="bars__plot">
        <div v-for="b in bars" :key="b.period" class="bars__col">
          <div class="bars__pair">
            <el-tooltip :content="`总收入 ${big(b.revenue)}`" placement="top">
              <div class="bar bar--rev" :style="{ height: pctOf(b.revenue) + '%' }" />
            </el-tooltip>
            <el-tooltip :content="`归母净利润 ${big(b.profit)}`" placement="top">
              <div class="bar" :class="(b.profit ?? 0) < 0 ? 'bar--loss' : 'bar--np'" :style="{ height: pctOf(b.profit) + '%' }" />
            </el-tooltip>
          </div>
          <div class="bars__label">{{ b.period }}</div>
        </div>
      </div>
    </div>

    <el-tabs v-model="statement" class="tabs">
      <el-tab-pane v-for="s in STATEMENTS" :key="s.value" :label="s.label" :name="s.value" />
    </el-tabs>
    <div v-if="grid.length" class="dtable-wrap reports">
      <table class="dtable">
        <thead>
          <tr><th class="field">项目</th><th v-for="r in shown" :key="r.periodText" class="r">{{ r.periodText }}</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in grid" :key="row.id" :class="{ group: row.group }">
            <td class="field">{{ row.name }}</td>
            <template v-if="!row.group">
              <td v-for="(c, i) in row.cells" :key="i" class="r num">
                <div :class="negative(c?.value)">{{ fmt(c) }}</div>
                <div v-if="yoy(c)" class="yoy" :class="trend(c?.yoy)">{{ yoy(c) }}</div>
              </td>
            </template>
            <td v-else :colspan="shown.length" />
          </tr>
        </tbody>
      </table>
    </div>
    <el-empty v-else-if="!loading && !error" :image-size="60" description="没有这类报表（基金没有财报；池外个股要先跑全量回补）" />
  </section>
</template>

<style scoped>
.bars { margin-bottom: 4px; }
.bars__legend { display: flex; gap: 14px; font-size: 11px; color: var(--el-text-color-secondary); margin-bottom: 6px; }
.sw { display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-right: 4px; vertical-align: -1px; }
.sw--rev, .bar--rev { background: var(--el-color-primary-light-5); }
.sw--np, .bar--np { background: var(--tr-up); }
.bar--loss { background: var(--tr-down); }
.bars__plot { display: flex; gap: 10px; align-items: flex-end; height: 140px; }
.bars__col { flex: 1; display: flex; flex-direction: column; height: 100%; min-width: 0; }
.bars__pair { flex: 1; display: flex; gap: 3px; align-items: flex-end; justify-content: center; }
.bar { width: 40%; max-width: 22px; border-radius: 2px 2px 0 0; min-height: 1px; }
.bars__label { text-align: center; font-size: 11px; color: var(--el-text-color-secondary); margin-top: 4px; white-space: nowrap; }
.tabs { margin-top: 8px; }
.tabs :deep(.el-tabs__header) { margin-bottom: 4px; }
.reports { max-height: 520px; overflow-y: auto; }
.reports thead th { position: sticky; top: 0; z-index: 1; background: var(--el-bg-color); }
.dtable { font-size: 12px; }
.field { min-width: 200px; position: sticky; left: 0; background: var(--el-bg-color); }
tr.group td { font-weight: 600; color: var(--el-text-color-primary); background: var(--el-fill-color-lighter); }
.yoy { font-size: 10px; line-height: 1.2; }
</style>
