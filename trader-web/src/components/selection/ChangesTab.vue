<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Rating, ScoreView } from '../../api/selection'
import { sectorCn } from '../../lib/sector'
import { EXCLUSION_LABEL, RATING_RANK, score } from '../../lib/selection'
import RatingTag from './RatingTag.vue'

/**
 * Rating changes vs the previous score date. Entries into and drops out of STRONG_BUY get their own lists (step 3's
 * "trigger only after N consecutive days" looks at STRONG_BUY); all other upgrades and downgrades share one table;
 * plus a ranking of consecutive STRONG_BUY days. A missing rating counts as rank 5, below STRONG_SELL.
 */
const props = defineProps<{ rows: ScoreView[]; prev: ScoreView[] | null; prevDate: string | null; names: Map<string, string | null> }>()
const emit = defineEmits<{ open: [row: ScoreView] }>()

interface Change { row: ScoreView; from: Rating | null; to: Rating | null; delta: number }

const prevById = computed(() => new Map((props.prev ?? []).map((r) => [r.instrumentId, r])))
const changes = computed<Change[]>(() => {
  const out: Change[] = []
  for (const r of props.rows) {
    const p = prevById.value.get(r.instrumentId)
    const from = p?.rating ?? null
    const to = r.rating
    if (from === to) continue
    const delta = (from ? RATING_RANK[from] : 5) - (to ? RATING_RANK[to] : 5)   // positive = upgrade
    out.push({ row: r, from, to, delta })
  }
  return out
})
const newStrongBuy = computed(() => changes.value.filter((c) => c.to === 'STRONG_BUY'))
const droppedStrongBuy = computed(() => changes.value.filter((c) => c.from === 'STRONG_BUY'))
const others = computed(() => changes.value.filter((c) => c.to !== 'STRONG_BUY' && c.from !== 'STRONG_BUY')
  .sort((a, b) => b.delta - a.delta || a.row.symbol.localeCompare(b.row.symbol)))
const upgrades = computed(() => changes.value.filter((c) => c.delta > 0).length)
const downgrades = computed(() => changes.value.filter((c) => c.delta < 0).length)

const streakTop = computed(() => props.rows.filter((r) => r.sbStreak > 0).sort((a, b) => b.sbStreak - a.sbStreak).slice(0, 20))
const othersOpen = ref<string[]>([])
const reason = (r: ScoreView) => (r.exclusion ? EXCLUSION_LABEL[r.exclusion] : r.rating ? '' : '因子不齐')
</script>

<template>
  <div class="tab">
    <el-alert v-if="!prev" type="info" :closable="false" show-icon title="没有更早的评分日，无从比较" />
    <template v-else>
      <div class="kv-grid">
        <div class="kv"><label>对比</label><b>{{ prevDate }} → {{ rows[0]?.tradeDate }}</b></div>
        <div class="kv"><label>新进强烈买入</label><b class="num up">{{ newStrongBuy.length }}</b></div>
        <div class="kv"><label>跌出强烈买入</label><b class="num down">{{ droppedStrongBuy.length }}</b></div>
        <div class="kv"><label>升级 / 降级</label><b class="num">{{ upgrades }} / {{ downgrades }}</b></div>
      </div>

      <div class="cols">
        <section class="panel">
          <div class="panel__head"><h3>新进强烈买入</h3></div>
          <table class="dtable">
            <thead><tr><th>代码</th><th>板块</th><th>之前</th><th class="r">合成分</th></tr></thead>
            <tbody>
              <tr v-for="c in newStrongBuy" :key="c.row.instrumentId" class="clickable" @click="emit('open', c.row)">
                <td><b>{{ c.row.symbol }}</b><span v-if="names.get(c.row.symbol)" class="name">{{ names.get(c.row.symbol) }}</span></td>
                <td>{{ sectorCn(c.row.sector) ?? '—' }}</td>
                <td><RatingTag :rating="c.from" /></td>
                <td class="r num">{{ score(c.row.composite) }}</td>
              </tr>
              <tr v-if="!newStrongBuy.length"><td colspan="4" class="empty">没有</td></tr>
            </tbody>
          </table>
        </section>
        <section class="panel">
          <div class="panel__head"><h3>跌出强烈买入</h3></div>
          <table class="dtable">
            <thead><tr><th>代码</th><th>板块</th><th>现在</th><th class="r">合成分</th></tr></thead>
            <tbody>
              <tr v-for="c in droppedStrongBuy" :key="c.row.instrumentId" class="clickable" @click="emit('open', c.row)">
                <td><b>{{ c.row.symbol }}</b><span v-if="names.get(c.row.symbol)" class="name">{{ names.get(c.row.symbol) }}</span></td>
                <td>{{ sectorCn(c.row.sector) ?? '—' }}</td>
                <td><RatingTag :rating="c.to" /><span v-if="reason(c.row)" class="muted"> {{ reason(c.row) }}</span></td>
                <td class="r num">{{ score(c.row.composite) }}</td>
              </tr>
              <tr v-if="!droppedStrongBuy.length"><td colspan="4" class="empty">没有</td></tr>
            </tbody>
          </table>
        </section>
      </div>

      <el-collapse v-model="othersOpen">
        <el-collapse-item name="others" :title="`其余评级变动 ${others.length} 只（升级在前）`">
          <table class="dtable">
            <thead><tr><th>代码</th><th>板块</th><th>之前</th><th>现在</th><th class="r">合成分</th></tr></thead>
            <tbody>
              <tr v-for="c in others" :key="c.row.instrumentId" class="clickable" @click="emit('open', c.row)">
                <td><b>{{ c.row.symbol }}</b><span v-if="names.get(c.row.symbol)" class="name">{{ names.get(c.row.symbol) }}</span></td>
                <td>{{ sectorCn(c.row.sector) ?? '—' }}</td>
                <td><RatingTag :rating="c.from" /></td>
                <td><RatingTag :rating="c.to" /><span v-if="reason(c.row)" class="muted"> {{ reason(c.row) }}</span></td>
                <td class="r num">{{ score(c.row.composite) }}</td>
              </tr>
            </tbody>
          </table>
        </el-collapse-item>
      </el-collapse>
    </template>

    <section class="panel">
      <div class="panel__head">
        <h3>连续强烈买入天数排行</h3>
        <span class="panel__src">截至当天连续评为强烈买入的交易日数（第 3 步「持续 N 天才触发」的输入，本期只记录不触发）</span>
      </div>
      <table class="dtable">
        <thead><tr><th class="r">#</th><th>代码</th><th>板块</th><th class="r">连续天数</th><th class="r">合成分</th></tr></thead>
        <tbody>
          <tr v-for="(r, i) in streakTop" :key="r.instrumentId" class="clickable" @click="emit('open', r)">
            <td class="r muted num">{{ i + 1 }}</td>
            <td><b>{{ r.symbol }}</b><span v-if="names.get(r.symbol)" class="name">{{ names.get(r.symbol) }}</span></td>
            <td>{{ sectorCn(r.sector) ?? '—' }}</td>
            <td class="r num"><b>{{ r.sbStreak }}</b></td>
            <td class="r num">{{ score(r.composite) }}</td>
          </tr>
          <tr v-if="!streakTop.length"><td colspan="5" class="empty">当天没有强烈买入</td></tr>
        </tbody>
      </table>
    </section>
  </div>
</template>

<style scoped>
.tab { display: flex; flex-direction: column; gap: 12px; }
.cols { display: grid; grid-template-columns: repeat(auto-fit, minmax(360px, 1fr)); gap: 12px; }
.dtable { font-size: 13px; }
.name { margin-left: 8px; font-size: 12px; color: var(--el-text-color-secondary); }
.up { color: var(--tr-up); }
.down { color: var(--tr-down); }
</style>
