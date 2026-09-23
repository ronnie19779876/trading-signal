<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  SCENARIOS, SCENARIO_LABEL, valuationApi,
  type ModelRequest, type SavedModel, type Scenario, type Segment, type SotpBasis, type SotpResult,
} from '../../api/valuation'
import { big, errMsg, num, numMax } from '../../lib/format'

/**
 * 分部估值（SOTP）：把公司按业务线拆开估值。
 *
 * 券商只给合并报表，没有分部量价、也没有一致预期，所以每条业务线的量、价、净利率、本益比都得自己填；
 * 这个面板负责自动带入公司级底座、做算术、并把**自相矛盾的假设**指出来（净利率口径核对、单因子敏感度、反推）。
 * 它不预测、不给建议，也不联动入场信号与纸面账本。
 */
const props = defineProps<{ symbol: string }>()

const basis = ref<SotpBasis | null>(null)
const models = ref<SavedModel[]>([])
const result = ref<SotpResult | null>(null)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const currentId = ref<number | null>(null)

/** 表单。净利率与要求回报率在界面上是**百分数**，发给后端前换成小数。 */
const form = ref({
  name: '',
  asOf: '',
  targetYear: new Date().getFullYear() + 4,
  discountPct: 10,
  targetShares: 0,
  targetNetCash: 0,
  note: '',
  segments: [] as { name: string; scopeNote: string; cases: Record<Scenario, { volume: number; price: number; marginPct: number; pe: number }> }[],
})

const applicable = computed(() => basis.value?.applicability.verdict !== 'NOT_APPLICABLE')
const alertType = computed(() => (basis.value?.applicability.verdict === 'NOT_APPLICABLE' ? 'error' : 'warning'))

function blankSegment() {
  const cases = {} as Record<Scenario, { volume: number; price: number; marginPct: number; pe: number }>
  for (const s of SCENARIOS) cases[s] = { volume: 0, price: 0, marginPct: 0, pe: 0 }
  return { name: '', scopeNote: '', cases }
}

function resetForm() {
  const b = basis.value
  form.value = {
    name: '',
    asOf: b?.priceDate ?? new Date().toISOString().slice(0, 10),
    targetYear: new Date().getFullYear() + 4,
    discountPct: 10,
    targetShares: b?.shares.outstanding ?? 0,
    targetNetCash: b?.netCash.netCash ?? 0,
    note: '',
    segments: [blankSegment()],
  }
  currentId.value = null
  result.value = null
}

function loadModel(m: SavedModel) {
  currentId.value = m.id
  form.value = {
    name: m.name,
    asOf: m.asOf,
    targetYear: m.targetYear,
    discountPct: m.discountRate * 100,
    targetShares: m.targetShares,
    targetNetCash: m.targetNetCash,
    note: m.note ?? '',
    segments: m.segments.map((s) => {
      const cases = {} as Record<Scenario, { volume: number; price: number; marginPct: number; pe: number }>
      for (const k of SCENARIOS) {
        const c = s.cases[k]
        cases[k] = { volume: c?.volume ?? 0, price: c?.price ?? 0, marginPct: (c?.netMargin ?? 0) * 100, pe: c?.pe ?? 0 }
      }
      return { name: s.name, scopeNote: s.scopeNote, cases }
    }),
  }
  result.value = m.result
}

function request(): ModelRequest {
  const segments: Segment[] = form.value.segments.map((s) => {
    const cases: Segment['cases'] = {}
    for (const k of SCENARIOS) {
      const c = s.cases[k]
      cases[k] = { volume: c.volume, price: c.price, netMargin: c.marginPct / 100, pe: c.pe }
    }
    return { name: s.name, scopeNote: s.scopeNote, cases }
  })
  return {
    name: form.value.name || null,
    asOf: form.value.asOf,
    targetYear: form.value.targetYear,
    discountRate: form.value.discountPct / 100,
    targetShares: form.value.targetShares,
    targetNetCash: form.value.targetNetCash,
    note: form.value.note || null,
    segments,
  }
}

async function load() {
  if (!props.symbol) return
  loading.value = true
  error.value = ''
  try {
    const [b, list] = await Promise.all([valuationApi.inputs(props.symbol), valuationApi.models(props.symbol)])
    basis.value = b
    models.value = list
    if (list.length) loadModel(list[0])
    else resetForm()
  } catch (e) {
    error.value = errMsg(e)
    basis.value = null
    models.value = []
  } finally {
    loading.value = false
  }
}

async function calc() {
  busy.value = true
  error.value = ''
  try {
    result.value = await valuationApi.calc(props.symbol, request())
  } catch (e) {
    error.value = errMsg(e)
    result.value = null
  } finally {
    busy.value = false
  }
}

async function save() {
  if (!form.value.name.trim()) {
    ElMessage.warning('先给方案起个名字，同一只标的可以存多套')
    return
  }
  busy.value = true
  error.value = ''
  try {
    const saved = await valuationApi.save(props.symbol, request())
    models.value = await valuationApi.models(props.symbol)
    loadModel(saved)
    ElMessage.success(`已保存「${saved.name}」`)
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    busy.value = false
  }
}

async function remove() {
  if (currentId.value === null) return
  const name = form.value.name
  try {
    await ElMessageBox.confirm(`删除方案「${name}」？删掉就找不回来了。`, '确认', { type: 'warning' })
  } catch {
    return
  }
  busy.value = true
  try {
    await valuationApi.remove(currentId.value)
    models.value = await valuationApi.models(props.symbol)
    if (models.value.length) loadModel(models.value[0])
    else resetForm()
    ElMessage.success(`已删除「${name}」`)
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    busy.value = false
  }
}

const useShares = (v: number | null) => { if (v) form.value.targetShares = Math.round(v) }
const useNetCash = () => { if (basis.value?.netCash.netCash !== null && basis.value) form.value.targetNetCash = basis.value.netCash.netCash! }

watch(() => props.symbol, load, { immediate: true })
</script>

<template>
  <section class="panel">
    <header class="panel__head">
      <h3 class="panel__title">分部估值（SOTP）</h3>
      <span class="panel__src">量 × 价 = 营收 → × 净利率 = 净利 → × 本益比 = 业务价值；加上目标年净现金除以股数，再折回今天</span>
    </header>

    <el-alert v-if="error" :title="'出错：' + error" type="error" show-icon :closable="false" />
    <el-alert v-if="basis && basis.applicability.reasons.length" :type="alertType" show-icon :closable="false"
              :title="basis.applicability.verdict === 'NOT_APPLICABLE' ? '这只标的不适用分部估值' : '可以算，但这些前提要留意'">
      <ul class="reasons"><li v-for="r in basis.applicability.reasons" :key="r">{{ r }}</li></ul>
    </el-alert>

    <template v-if="basis && applicable">
      <!-- 底座：系统带入，只读 -->
      <div class="kv-grid">
        <div class="kv"><label>现价（{{ basis.priceDate ?? '—' }}）</label><b class="num">{{ numMax(basis.price) }}</b></div>
        <div class="kv"><label>净现金（{{ basis.netCash.periodText ?? '—' }}）</label><b class="num">{{ big(basis.netCash.netCash) }}</b></div>
        <div class="kv"><label>现金及短投</label><b class="num">{{ big(basis.netCash.cashAndShortTerm) }}</b></div>
        <div class="kv"><label>有息借款</label><b class="num">{{ big(basis.netCash.borrowings) }}</b></div>
        <div class="kv"><label>融资租赁（未计入）</label><b class="num">{{ big(basis.netCash.leases) }}</b></div>
        <div class="kv"><label>TTM 营收</label><b class="num">{{ big(basis.ttmRevenue) }}</b></div>
        <div class="kv"><label>TTM 归母净利</label><b class="num">{{ big(basis.ttmNetIncome) }}</b></div>
        <div class="kv"><label>合并净利率</label><b class="num">{{ basis.netMarginTtm === null ? '—' : num(basis.netMarginTtm * 100) + '%' }}</b></div>
        <div class="kv"><label>市盈率中位数（5 年）</label><b class="num">{{ numMax(basis.peMedian) }}</b></div>
      </div>
      <p class="panel__foot shares">
        股数三种口径实测互不相同，系统不替你选：
        <el-button link type="primary" @click="useShares(basis.shares.outstanding)">券商流通股 {{ big(basis.shares.outstanding) }}</el-button>
        <el-button link type="primary" @click="useShares(basis.shares.byMarketCap)">市值 ÷ 现价 {{ big(basis.shares.byMarketCap) }}</el-button>
        <el-button link type="primary" @click="useShares(basis.shares.byDilutedEps)">净利 ÷ 稀释每股收益 {{ big(basis.shares.byDilutedEps) }}</el-button>
      </p>

      <!-- 方案 -->
      <div class="toolbar">
        <el-select v-model="currentId" size="small" placeholder="选一套已存的方案" style="width: 200px" clearable
                   @change="(id: number | null) => { const m = models.find(x => x.id === id); if (m) loadModel(m); else resetForm() }">
          <el-option v-for="m in models" :key="m.id" :value="m.id" :label="m.name" />
        </el-select>
        <el-button size="small" @click="resetForm">新建</el-button>
        <el-button size="small" :disabled="currentId === null || busy" @click="remove">删除</el-button>
        <el-input v-model="form.name" size="small" placeholder="方案名称" style="width: 160px" />
        <el-input v-model="form.asOf" size="small" placeholder="基准日 2026-09-19" style="width: 140px" />
        <span class="lbl">目标年</span><el-input-number v-model="form.targetYear" size="small" :min="2026" :max="2060" :controls="false" style="width: 80px" />
        <span class="lbl">要求回报率 %</span><el-input-number v-model="form.discountPct" size="small" :min="0" :max="60" :step="1" :controls="false" style="width: 70px" />
        <span class="lbl">目标年股数</span><el-input-number v-model="form.targetShares" size="small" :controls="false" style="width: 150px" />
        <span class="lbl">目标年净现金</span><el-input-number v-model="form.targetNetCash" size="small" :controls="false" style="width: 150px" />
        <el-button link type="primary" @click="useNetCash">用当前值</el-button>
      </div>

      <!-- 业务线 -->
      <div v-for="(s, i) in form.segments" :key="i" class="seg">
        <div class="seg__head">
          <el-input v-model="s.name" size="small" placeholder="业务线名称" style="width: 160px" />
          <el-input v-model="s.scopeNote" size="small" placeholder="口径备注（必填）：算什么、不算什么" style="flex: 1; min-width: 240px" />
          <el-button size="small" :disabled="form.segments.length <= 1" @click="form.segments.splice(i, 1)">删除</el-button>
        </div>
        <table class="seg__tbl">
          <thead><tr><th>情景</th><th>量</th><th>单价</th><th>净利率 %</th><th>本益比</th><th>营收</th><th>净利</th><th>业务价值</th></tr></thead>
          <tbody>
            <tr v-for="k in SCENARIOS" :key="k">
              <td class="sc">{{ SCENARIO_LABEL[k] }}</td>
              <td><el-input-number v-model="s.cases[k].volume" size="small" :controls="false" style="width: 110px" /></td>
              <td><el-input-number v-model="s.cases[k].price" size="small" :controls="false" style="width: 110px" /></td>
              <td><el-input-number v-model="s.cases[k].marginPct" size="small" :controls="false" :step="1" style="width: 80px" /></td>
              <td><el-input-number v-model="s.cases[k].pe" size="small" :controls="false" style="width: 70px" /></td>
              <td class="num">{{ big(s.cases[k].volume * s.cases[k].price) }}</td>
              <td class="num">{{ big(s.cases[k].volume * s.cases[k].price * s.cases[k].marginPct / 100) }}</td>
              <td class="num strong">{{ big(s.cases[k].volume * s.cases[k].price * s.cases[k].marginPct / 100 * s.cases[k].pe) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="actions">
        <el-button size="small" @click="form.segments.push(blankSegment())">加一条业务线</el-button>
        <el-button size="small" type="primary" :loading="busy" @click="calc">试算</el-button>
        <el-button size="small" :loading="busy" @click="save">保存</el-button>
        <el-input v-model="form.note" size="small" placeholder="备注" style="width: 220px" />
      </div>

      <!-- 结果 -->
      <template v-if="result">
        <table class="res">
          <thead>
            <tr><th>情景</th><th>业务价值合计</th><th>股权价值</th><th>{{ form.targetYear }} 年股价</th><th>折回今天</th><th>相对现价</th></tr>
          </thead>
          <tbody>
            <tr v-for="k in SCENARIOS" :key="k" :class="{ base: k === 'BASE' }">
              <td class="sc">{{ SCENARIO_LABEL[k] }}</td>
              <td class="num">{{ big(result.scenarios[k].segmentTotal) }}</td>
              <td class="num">{{ big(result.scenarios[k].equityValue) }}</td>
              <td class="num">{{ num(result.scenarios[k].targetPrice) }}</td>
              <td class="num strong">{{ num(result.scenarios[k].presentValue) }}</td>
              <td class="num" :class="(result.scenarios[k].upsidePct ?? 0) >= 0 ? 'up' : 'down'">
                {{ result.scenarios[k].upsidePct === null ? '—' : num(result.scenarios[k].upsidePct) + '%' }}
              </td>
            </tr>
          </tbody>
        </table>
        <p class="panel__foot">
          折现期 {{ num(result.horizonYears) }} 年、系数 {{ num(result.discountFactor, 4) }}。
          <b>只有「折回今天」那一列能和现价比</b>——业务价值 × 本益比得到的是 {{ form.targetYear }} 年的价值。
        </p>

        <div class="kv-grid">
          <div class="kv"><label>隐含合并净利率（基准）</label><b class="num">{{ num(result.marginCheck.impliedNetMargin * 100) }}%</b></div>
          <div class="kv"><label>最近一期实际</label><b class="num">{{ result.marginCheck.actualNetMargin === null ? '—' : num(result.marginCheck.actualNetMargin * 100) + '%' }}</b></div>
          <div class="kv"><label>偏离</label>
            <b class="num" :class="{ down: !result.marginCheck.ok }">
              {{ result.marginCheck.deviationPp === null ? '无从核对' : num(result.marginCheck.deviationPp) + ' pp' }}
            </b>
          </div>
        </div>
        <el-alert v-if="!result.marginCheck.ok" type="warning" show-icon :closable="false"
                  title="分业务净利率加起来对不上公司：每块都很赚、合并却做不到，说明假设自相矛盾" />

        <h4 class="sub">单因子敏感度（只动一条，其余保持基准）</h4>
        <table class="res">
          <thead><tr><th>业务线</th><th>熊（折今）</th><th>牛（折今）</th><th>摆幅</th></tr></thead>
          <tbody>
            <tr v-for="s in result.sensitivities" :key="s.segment">
              <td>{{ s.segment }}</td>
              <td class="num">{{ num(s.lowPresentValue) }}</td>
              <td class="num">{{ num(s.highPresentValue) }}</td>
              <td class="num strong">{{ num(s.swing) }}</td>
            </tr>
          </tbody>
        </table>
        <p class="panel__foot">摆幅最大的那条决定了整个估值；其余业务线怎么摆都改不了结论。</p>

        <template v-if="result.reverse">
          <h4 class="sub">反推：现价已经押注了什么</h4>
          <div class="kv-grid">
            <div class="kv"><label>{{ form.targetYear }} 年需达到股价</label><b class="num">{{ num(result.reverse.requiredTargetPrice) }}</b></div>
            <div class="kv"><label>对应市值</label><b class="num">{{ big(result.reverse.requiredMarketCap) }}</b></div>
            <div class="kv"><label>按市盈率中位数需净利</label><b class="num">{{ big(result.reverse.requiredNetIncome) }}</b></div>
            <div class="kv"><label>所需年化增长</label>
              <b class="num">{{ result.reverse.requiredNetIncomeCagr === null ? '—' : num(result.reverse.requiredNetIncomeCagr * 100) + '%' }}</b>
            </div>
          </div>
          <p class="panel__foot">按你填的要求回报率，现价隐含的目标年水平。所需净利用的是近 5 年市盈率中位数 {{ numMax(basis.peMedian) }}。</p>
        </template>
        <p v-else class="panel__foot">取不到市盈率基准，反推不给结论——硬编一个倍数只会把猜测伪装成计算。</p>
      </template>
    </template>

    <div v-else-if="loading" v-loading="true" class="loading-box" />
    <el-empty v-else-if="!error" :image-size="60" description="没有底座数据" />

    <p class="panel__foot warn">
      券商只给合并报表，<b>没有分部量价、没有一致预期</b>，所以每条业务线的假设都是你自己的判断。
      这个工具只做算术与一致性核对，不预测、不给建议，也不参与入场信号与纸面账本。
    </p>
  </section>
</template>

<style scoped>
.loading-box { min-height: 120px; }
.reasons { margin: 4px 0 0; padding-left: 18px; }
.shares { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 10px; }
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 10px 0; }
.toolbar > * { flex: none; }
.lbl { font-size: 12px; color: var(--el-text-color-secondary); }
.seg { border: 1px solid var(--el-border-color-lighter); border-radius: 6px; padding: 8px 10px; margin-bottom: 8px; }
.seg__head { display: flex; gap: 8px; align-items: center; margin-bottom: 6px; flex-wrap: wrap; }
.seg__tbl, .res { width: 100%; border-collapse: collapse; font-size: 13px; }
.seg__tbl th, .res th { text-align: right; font-weight: 500; color: var(--el-text-color-secondary); padding: 4px 6px; }
.seg__tbl td, .res td { text-align: right; padding: 3px 6px; }
.seg__tbl th:first-child, .res th:first-child, .seg__tbl td.sc, .res td:first-child { text-align: left; }
.res tbody tr.base { background: var(--el-fill-color-light); }
.strong { font-weight: 600; }
.up { color: var(--tr-up); }
.down { color: var(--tr-down); }
.actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin: 10px 0; }
.sub { margin: 14px 0 6px; font-size: 14px; }
.warn { margin-top: 12px; }
</style>
