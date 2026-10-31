<script setup lang="ts">
import { computed } from 'vue'
import type { ScoreView } from '../../api/selection'
import { sectorCn } from '../../lib/sector'
import { RATINGS, RATING_COLOR, RATING_LABEL, score } from '../../lib/selection'

/**
 * Sectors: number of instruments per rating tier in each sector (horizontally stacked) and the median composite
 * score. Scores are within-sector percentiles, so a higher composite in one sector than another does not mean "a
 * better sector"; this view only shows distributions.
 */
const props = defineProps<{ rows: ScoreView[] }>()

const sectors = computed(() => {
  const by = new Map<string, ScoreView[]>()
  for (const r of props.rows) {
    if (!r.sector || r.exclusion) continue
    by.set(r.sector, [...(by.get(r.sector) ?? []), r])
  }
  return [...by.entries()].map(([sector, list]) => {
    const rated = list.filter((r) => r.rating)
    const comp = rated.map((r) => r.composite as number).sort((a, b) => a - b)
    const median = comp.length ? (comp.length % 2 ? comp[(comp.length - 1) / 2] : (comp[comp.length / 2 - 1] + comp[comp.length / 2]) / 2) : null
    return {
      sector,
      total: list.length,
      rated: rated.length,
      median,
      counts: RATINGS.map((rt) => ({ rating: rt, n: rated.filter((r) => r.rating === rt).length })),
    }
  }).sort((a, b) => b.total - a.total)
})
const max = computed(() => Math.max(1, ...sectors.value.map((s) => s.rated)))
const tooSmall = computed(() => props.rows.filter((r) => r.exclusion === 'SECTOR_TOO_SMALL'))
</script>

<template>
  <div class="tab">
    <div class="legend">
      <span v-for="r in RATINGS" :key="r" class="legend__item"><i :style="{ background: RATING_COLOR[r] }" />{{ RATING_LABEL[r] }}</span>
      <span class="muted">· 打分是板块内百分位，板块之间合成分高低不可比，只看分布</span>
    </div>
    <section class="panel">
      <table class="dtable">
        <thead><tr><th>板块</th><th class="r">合格</th><th class="r">已评级</th><th style="width: 50%">各档评级只数</th><th class="r">合成分中位数</th></tr></thead>
        <tbody>
          <tr v-for="s in sectors" :key="s.sector">
            <td><b>{{ sectorCn(s.sector) ?? s.sector }}</b></td>
            <td class="r num">{{ s.total }}</td>
            <td class="r num">{{ s.rated }}</td>
            <td>
              <div class="stack" :style="{ width: `${(s.rated / max) * 100}%` }">
                <span v-for="c in s.counts" v-show="c.n" :key="c.rating" class="stack__seg" :style="{ flex: c.n, background: RATING_COLOR[c.rating] }"
                      :title="`${RATING_LABEL[c.rating]} ${c.n} 只`">{{ c.n }}</span>
              </div>
            </td>
            <td class="r num">{{ score(s.median) }}</td>
          </tr>
        </tbody>
      </table>
      <p v-if="tooSmall.length" class="panel__foot">
        板块合格标的不足 8 只、整组不评：{{ [...new Set(tooSmall.map((r) => sectorCn(r.sector) ?? r.sector))].join('、') }}
        （{{ tooSmall.map((r) => r.symbol).join('、') }}）
      </p>
    </section>
  </div>
</template>

<style scoped>
.tab { display: flex; flex-direction: column; gap: 12px; }
.legend { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; font-size: 12px; }
.legend__item { display: inline-flex; align-items: center; gap: 4px; }
.legend__item i { width: 12px; height: 12px; border-radius: 2px; display: inline-block; }
.dtable { font-size: 13px; }
.stack { display: flex; height: 18px; border-radius: 3px; overflow: hidden; min-width: 40px; }
.stack__seg { display: flex; align-items: center; justify-content: center; font-size: 11px; color: var(--el-text-color-primary); min-width: 14px; }
</style>
