<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ValuationSnapshot } from '../../api/fundamentals'
import type { InstrumentView } from '../../api/marketdata'
import { big, negative, numMax } from '../../lib/format'
import { sectorCn } from '../../lib/sector'

/**
 * 全市场估值筛选表：某一天全部标的的估值（/api/fundamentals/valuations），名称、行业、指数、池角色从标的列表合并。
 * 全是券商原值：亏损股的市盈率为负是真实数据，照常显示、排序时按数值排。分页显示，筛选与排序作用于全部标的。
 */
const props = defineProps<{
  date: string | null
  valuations: ValuationSnapshot[]
  universe: InstrumentView[]
  selected: string | null
  loaded: boolean
}>()
const emit = defineEmits<{ select: [symbol: string] }>()

type Scope = 'all' | 'pool' | 'SP500' | 'NDX100'
type SortKey = 'symbol' | 'marketCap' | 'peTtm' | 'pe' | 'pb' | 'dividendYieldTtm' | 'turnoverRate'

const scope = ref<Scope>('all')
const sector = ref<string>('')
const keyword = ref('')
const sortKey = ref<SortKey>('marketCap')
const sortDesc = ref(true)

const byInstrument = computed(() => new Map(props.universe.map((u) => [u.symbol, u])))

const rows = computed(() =>
  props.valuations.map((v) => {
    const u = byInstrument.value.get(v.instrument.symbol)
    return {
      symbol: v.instrument.symbol,
      name: u?.nameCn ?? u?.name ?? null,
      sector: sectorCn(u?.sector) ?? null,
      indexes: u?.indexes ?? [],
      role: u?.role ?? null,
      v,
    }
  }),
)

const sectors = computed(() => [...new Set(rows.value.map((r) => r.sector).filter((s): s is string => !!s))].sort())

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  const list = rows.value.filter((r) => {
    if (scope.value === 'pool' && r.role !== 'POOL' && r.role !== 'HOLDING') return false
    if ((scope.value === 'SP500' || scope.value === 'NDX100') && !r.indexes.includes(scope.value)) return false
    if (sector.value && r.sector !== sector.value) return false
    if (kw && !r.symbol.toLowerCase().includes(kw) && !(r.name ?? '').toLowerCase().includes(kw)) return false
    return true
  })
  const key = sortKey.value
  const dir = sortDesc.value ? -1 : 1
  return list.sort((a, b) => {
    if (key === 'symbol') return dir * a.symbol.localeCompare(b.symbol)
    const x = a.v[key]
    const y = b.v[key]
    if (x === null && y === null) return 0
    if (x === null) return 1 // 空值总在最后
    if (y === null) return -1
    return dir * (x - y)
  })
})

// ---- 分页：默认每页 20 只；筛选或排序一变回到第 1 页 ----
const page = ref(1)
const pageSize = ref(20)
const paged = computed(() => filtered.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
watch([scope, sector, keyword, sortKey, sortDesc, pageSize], () => (page.value = 1))

function sortBy(key: SortKey) {
  if (sortKey.value === key) sortDesc.value = !sortDesc.value
  else {
    sortKey.value = key
    sortDesc.value = key !== 'symbol'
  }
}
function arrow(key: SortKey) {
  return sortKey.value === key ? (sortDesc.value ? ' ↓' : ' ↑') : ''
}

const ROLE_LABEL: Record<string, string> = { HOLDING: '持仓', POOL: '池', BENCHMARK: '基准' }
const COLUMNS: { key: SortKey; label: string }[] = [
  { key: 'marketCap', label: '总市值' },
  { key: 'peTtm', label: '市盈率 TTM' },
  { key: 'pe', label: '市盈率' },
  { key: 'pb', label: '市净率' },
  { key: 'dividendYieldTtm', label: '股息率 TTM' },
  { key: 'turnoverRate', label: '换手率' },
]
function cell(key: SortKey, v: ValuationSnapshot): string {
  switch (key) {
    case 'marketCap': return big(v.marketCap)
    case 'dividendYieldTtm':
    case 'turnoverRate': return v[key] === null ? '—' : numMax(v[key]) + '%'
    default: return numMax(v[key as 'pe' | 'peTtm' | 'pb'])
  }
}
</script>

<template>
  <section class="panel">
    <div class="panel__head">
      <h3>全市场估值</h3>
      <span class="panel__src">{{ date ? date + ' 估值快照' : '还没有估值快照' }} · 券商原值，亏损股市盈率为负是真实数据 · 点一行看详情</span>
      <span class="panel__grow" />
      <span class="panel__src">{{ filtered.length }} / {{ rows.length }} 只</span>
    </div>
    <div class="filters">
      <el-radio-group v-model="scope" size="small">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="pool">池与持仓</el-radio-button>
        <el-radio-button value="SP500">标普 500</el-radio-button>
        <el-radio-button value="NDX100">纳指 100</el-radio-button>
      </el-radio-group>
      <el-select v-model="sector" size="small" clearable placeholder="全部行业" style="width: 140px">
        <el-option v-for="s in sectors" :key="s" :label="s" :value="s" />
      </el-select>
      <el-input v-model="keyword" size="small" clearable placeholder="代码或名称" style="width: 180px" />
    </div>
    <div class="dtable-wrap screen">
      <table class="dtable">
        <thead>
          <tr>
            <th class="sortable" @click="sortBy('symbol')">代码{{ arrow('symbol') }}</th>
            <th>行业</th>
            <th v-for="c in COLUMNS" :key="c.key" class="r sortable" @click="sortBy(c.key)">{{ c.label }}{{ arrow(c.key) }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in paged" :key="r.symbol" class="clickable" :class="{ picked: r.symbol === selected }" @click="emit('select', r.symbol)">
            <td>
              <b>{{ r.symbol }}</b>
              <span v-if="r.name" class="name">{{ r.name }}</span>
              <el-tag v-if="r.role" size="small" :type="r.role === 'HOLDING' ? 'success' : 'info'" effect="plain" class="tag">{{ ROLE_LABEL[r.role] }}</el-tag>
              <el-tag v-if="r.v.suspended" size="small" type="warning" class="tag">停牌</el-tag>
            </td>
            <td class="muted-cell">{{ r.sector ?? '—' }}</td>
            <td v-for="c in COLUMNS" :key="c.key" class="r num" :class="c.key === 'pe' || c.key === 'peTtm' || c.key === 'pb' ? negative(r.v[c.key]) : ''">
              {{ cell(c.key, r.v) }}
            </td>
          </tr>
          <tr v-if="!filtered.length">
            <td :colspan="2 + COLUMNS.length" class="empty">{{ loaded ? '没有符合条件的标的' : '加载中…' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="filtered.length > pageSize" class="pager">
      <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="filtered.length"
                     :page-sizes="[20, 50, 100]" layout="total, sizes, prev, pager, next, jumper" size="small" background />
    </div>
  </section>
</template>

<style scoped>
.filters { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 8px; }
.pager { display: flex; justify-content: flex-end; margin-top: 10px; }
.sortable { cursor: pointer; user-select: none; }
.sortable:hover { color: var(--el-color-primary); }
.dtable { font-size: 13px; }
.name { margin-left: 8px; font-size: 12px; color: var(--el-text-color-secondary); }
.tag { margin-left: 6px; }
.muted-cell { color: var(--el-text-color-secondary); }
tr.picked td { background: var(--el-color-primary-light-9); }
</style>
