<script setup lang="ts">
import { FACTORS, score } from '../../lib/selection'
import type { ScoreView } from '../../api/selection'

/** The four factor scores (0~100, mean of within-sector percentiles) as four small horizontal bars; a missing factor shows an empty slot. */
defineProps<{ row: Pick<ScoreView, 'valueScore' | 'growthScore' | 'profitScore' | 'momentumScore'> }>()
</script>

<template>
  <div class="bars">
    <div v-for="f in FACTORS" :key="f.key" class="bar" :title="`${f.label} ${score(row[f.key])}`">
      <span class="bar__label">{{ f.label }}</span>
      <span class="bar__track"><span v-if="row[f.key] !== null" class="bar__fill" :style="{ width: `${row[f.key]}%` }" /></span>
      <span class="bar__val num">{{ score(row[f.key]) }}</span>
    </div>
  </div>
</template>

<style scoped>
.bars { display: grid; grid-template-columns: repeat(4, minmax(64px, 1fr)); gap: 6px; }
.bar { display: flex; align-items: center; gap: 4px; font-size: 11px; }
.bar__label { color: var(--el-text-color-secondary); width: 24px; flex: none; }
.bar__track { flex: 1; height: 6px; border-radius: 3px; background: var(--el-fill-color); overflow: hidden; }
.bar__fill { display: block; height: 100%; background: var(--el-color-primary); opacity: 0.75; }
.bar__val { width: 20px; text-align: right; flex: none; color: var(--el-text-color-regular); }
</style>
