<script setup lang="ts">
import { GATES, GATE_LABEL } from './format'

/** 四门结论缩写（如 "PFPU"）画成四个色点：通过绿、不过灰、不可判定黄。 */
const props = defineProps<{ gates: string | null }>()
const VERDICT: Record<string, string> = { P: '通过', F: '不过', U: '不可判定' }
</script>

<template>
  <span v-if="props.gates" class="dots">
    <el-tooltip v-for="(g, i) in GATES" :key="g" :content="`${GATE_LABEL[g]}：${VERDICT[props.gates[i]] ?? '—'}`" placement="top">
      <span class="dot" :class="'dot--' + props.gates[i]"></span>
    </el-tooltip>
  </span>
  <span v-else class="muted">—</span>
</template>

<style scoped>
.dots { display: inline-flex; gap: 4px; align-items: center; }
.dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; background: var(--el-border-color); }
.dot--P { background: var(--el-color-success); }
.dot--F { background: var(--el-text-color-disabled); }
.dot--U { background: var(--el-color-warning); }
</style>
