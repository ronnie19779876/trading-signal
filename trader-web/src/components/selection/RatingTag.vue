<script setup lang="ts">
import type { Rating } from '../../api/selection'
import { RATING_EFFECT, RATING_LABEL, RATING_TAG } from '../../lib/selection'

/** Rating tag: strong tiers solid, second tiers outlined; no rating shows a dash. */
defineProps<{ rating: Rating | null }>()
</script>

<template>
  <el-tag v-if="rating" size="small" :type="RATING_TAG[rating]" :effect="RATING_EFFECT[rating]" disable-transitions>{{ RATING_LABEL[rating] }}</el-tag>
  <span v-else class="muted">—</span>
</template>
