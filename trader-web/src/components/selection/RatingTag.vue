<script setup lang="ts">
import type { Rating } from '../../api/selection'
import { RATING_EFFECT, RATING_LABEL, RATING_TAG } from '../../lib/selection'

/** 评级标签：强烈档实心、次一档描边；没有评级显示破折号。 */
defineProps<{ rating: Rating | null }>()
</script>

<template>
  <el-tag v-if="rating" size="small" :type="RATING_TAG[rating]" :effect="RATING_EFFECT[rating]" disable-transitions>{{ RATING_LABEL[rating] }}</el-tag>
  <span v-else class="muted">—</span>
</template>
