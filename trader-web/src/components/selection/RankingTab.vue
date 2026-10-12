<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Rating, ScoreView } from '../../api/selection'
import { usePager } from '../../composables/usePager'
import { sectorCn } from '../../lib/sector'
import { EXCLUSION_LABEL, RATINGS, RATING_LABEL, RATING_RANK, score } from '../../lib/selection'
import RatingTag from './RatingTag.vue'
import FactorBars from './FactorBars.vue'

/**
 * 评分榜：当天全表。默认按合成分从高到低，可按板块、评级筛、按代码搜、换排序列。
 * 被排除的标的另起一段（默认收起），列出排除原因——它们不占板块名额、不参与排名。
 */
const props = defineProps<{ rows: ScoreView[]; names: Map<string, string | null> }>()
const emit = defineEmits<{ open: [row: ScoreView] }>()

const sector = ref<string>('')
const ratings = ref<Rating[]>([])
const keyword = ref('')
type SortKey = 'composite' | 'valueScore' | 'growthScore' | 'profitScore' | 'momentumScore' | 'sbStreak'
const sortKey = ref<SortKey>('composite')
const SORTS: { value: SortKey; label: string }[] = [
  { value: 'composite', label: '合成分' },
  { value: 'valueScore', label: '估值' },
  { value: 'growthScore', label: '成长' },
  { value: 'profitScore', label: '盈利' },
  { value: 'momentumScore', label: '动能' },
  { value: 'sbStreak', label: '连续强烈买入' },
]

const sectors = computed(() => [...new Set(props.rows.map((r) => r.sector).filter((s): s is string => !!s))].sort())
const scored = computed(() => props.rows.filter((r) => !r.exclusion))
const excluded = computed(() => props.rows.filter((r) => r.exclusion))

const matches = (r: ScoreView) => {
  const k = keyword.value.trim().toUpperCase()
  if (k && !r.symbol.includes(k) && !(props.names.get(r.symbol) ?? '').includes(keyword.value.trim())) return false
  if (sector.value && r.sector !== sector.value) return false
  if (ratings.value.length && (!r.rating || !ratings.value.includes(r.rating))) return false
  return true
}

const filtered = computed(() => {
  const key = sortKey.value
  return scored.value.filter(matches).sort((a, b) => {
    const va = a[key] ?? -1
    const vb = b[key] ?? -1
    return vb - va || a.symbol.localeCompare(b.symbol)
  })
})
const { page, pageSize, paged, total } = usePager(filtered, 50, [sector, ratings, keyword, sortKey])

/** 当天每档评级的只数（强制分布 10/20/40/20/10） */
const distribution = computed(() => RATINGS.map((r) => ({ rating: r, count: scored.value.filter((s) => s.rating === r).length })))
const unrated = computed(() => scored.value.filter((r) => !r.rating).length)

const showExcluded = ref<string[]>([])
const excludedSorted = computed(() => [...excluded.value].sort((a, b) => (a.exclusion ?? '').localeCompare(b.exclusion ?? '') || a.symbol.localeCompare(b.symbol)))

function toggleRating(r: Rating) {
  ratings.value = ratings.value.includes(r) ? ratings.value.filter((x) => x !== r) : [...ratings.value, r]
}
const rankOf = (r: ScoreView) => (r.rating ? RATING_RANK[r.rating] : 9)
</script>

<template>
  <div class="tab">
    <div class="dist">
      <button v-for="d in distribution" :key="d.rating" class="dist__item" :class="{ 'dist__item--on': ratings.includes(d.rating) }"
              @click="toggleRating(d.rating)">
        <RatingTag :rating="d.rating" /> <b class="num">{{ d.count }}</b>
      </button>
      <span class="muted">已评级 {{ scored.length - unrated }} 只<template v-if="unrated">，因子不齐未评 {{ unrated }} 只</template>
        · 排除 {{ excluded.length }} 只 · 点评级可筛选</span>
    </div>

    <div class="toolbar">
      <el-input v-model="keyword" size="small" clearable placeholder="代码或中文名" style="width: 160px" />
      <el-select v-model="sector" size="small" clearable placeholder="全部板块" style="width: 150px">
        <el-option v-for="s in sectors" :key="s" :label="sectorCn(s) ?? s" :value="s" />
      </el-select>
      <el-select v-model="ratings" size="small" multiple collapse-tags clearable placeholder="全部评级" style="width: 170px">
        <el-option v-for="r in RATINGS" :key="r" :label="RATING_LABEL[r]" :value="r" />
      </el-select>
      <span class="muted">排序</span>
      <el-radio-group v-model="sortKey" size="small">
        <el-radio-button v-for="s in SORTS" :key="s.value" :value="s.value">{{ s.label }}</el-radio-button>
      </el-radio-group>
      <span class="muted">{{ filtered.length }} 只 · 点一行看详情</span>
    </div>

    <section class="panel">
      <div class="dtable-wrap">
        <table class="dtable">
          <thead>
            <tr>
              <th class="r">#</th><th>代码</th><th>板块</th><th>评级</th><th class="r">合成分</th><th class="r">全市场分位</th>
              <th style="min-width: 360px">四个因子（板块内百分位的平均，0~100）</th><th class="r">连续强烈买入</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in paged" :key="row.instrumentId" class="clickable" :class="`rk${rankOf(row)}`" @click="emit('open', row)">
              <td class="r muted num">{{ (page - 1) * pageSize + i + 1 }}</td>
              <td><b>{{ row.symbol }}</b><span v-if="names.get(row.symbol)" class="name">{{ names.get(row.symbol) }}</span></td>
              <td>{{ sectorCn(row.sector) ?? '—' }}</td>
              <td><RatingTag :rating="row.rating" /></td>
              <td class="r num"><b>{{ score(row.composite) }}</b></td>
              <td class="r num">{{ score(row.compositePct) }}</td>
              <td><FactorBars :row="row" /></td>
              <td class="r num">{{ row.sbStreak ? `${row.sbStreak} 天` : '—' }}</td>
            </tr>
            <tr v-if="!filtered.length">
              <td colspan="8" class="empty">没有符合条件的标的</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="total > pageSize" class="pager">
        <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="total" :page-sizes="[50, 100, 200]"
                       layout="total, sizes, prev, pager, next" size="small" background />
      </div>
    </section>

    <el-collapse v-if="excluded.length" v-model="showExcluded">
      <el-collapse-item name="excluded" :title="`当天不参与打分的 ${excluded.length} 只（不占板块名额、不参与排名）`">
        <table class="dtable">
          <thead><tr><th>代码</th><th>板块</th><th>原因</th><th>说明</th></tr></thead>
          <tbody>
            <tr v-for="row in excludedSorted" :key="row.instrumentId" class="clickable" @click="emit('open', row)">
              <td><b>{{ row.symbol }}</b><span v-if="names.get(row.symbol)" class="name">{{ names.get(row.symbol) }}</span></td>
              <td>{{ sectorCn(row.sector) ?? '—' }}</td>
              <td>{{ row.exclusion ? EXCLUSION_LABEL[row.exclusion] : '—' }}</td>
              <td class="muted wrap">{{ row.exclusionDetail }}</td>
            </tr>
          </tbody>
        </table>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<style scoped>
.tab { display: flex; flex-direction: column; gap: 12px; }
.toolbar, .dist { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.dist__item { display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px; border-radius: 6px; cursor: pointer;
  border: 1px solid var(--el-border-color-lighter); background: var(--el-fill-color-blank); font-size: 13px; }
.dist__item--on { border-color: var(--el-color-primary); box-shadow: 0 0 0 1px var(--el-color-primary) inset; }
.dtable { font-size: 13px; }
.wrap { white-space: normal; }
.name { margin-left: 8px; font-size: 12px; color: var(--el-text-color-secondary); }
.pager { display: flex; justify-content: flex-end; margin-top: 10px; }
</style>
