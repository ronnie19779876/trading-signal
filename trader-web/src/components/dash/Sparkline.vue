<script setup lang="ts">
import { computed } from 'vue'

/**
 * Sparkline (plain SVG). A canvas chart per table row is too heavy, and no interaction is needed here.
 * The color compares the first and last points and uses the up/down colors (tokens.css, US convention green up,
 * red down).
 */
const props = withDefaults(defineProps<{ points: number[]; width?: number; height?: number }>(), { width: 96, height: 26 })

const path = computed(() => {
  const p = props.points
  if (p.length < 2) return ''
  const min = Math.min(...p)
  const span = Math.max(...p) - min || 1 // avoid division by zero when the series is flat
  const dx = props.width / (p.length - 1)
  const h = props.height - 4 // 2px margin top and bottom so the extremes do not touch the edge
  return p.map((v, i) => `${i ? 'L' : 'M'}${(i * dx).toFixed(1)},${(2 + h - ((v - min) / span) * h).toFixed(1)}`).join(' ')
})
const up = computed(() => props.points.length >= 2 && props.points[props.points.length - 1] >= props.points[0])
</script>

<template>
  <svg :width="width" :height="height" :viewBox="`0 0 ${width} ${height}`" class="spark">
    <path v-if="path" :d="path" fill="none" :class="up ? 'spark--up' : 'spark--down'" stroke-width="1.5"
          stroke-linejoin="round" stroke-linecap="round" />
    <text v-else :x="width / 2" :y="height / 2 + 4" text-anchor="middle" class="spark__empty">无数据</text>
  </svg>
</template>

<style scoped>
.spark { display: block; }
.spark--up { stroke: var(--tr-up); }
.spark--down { stroke: var(--tr-down); }
.spark__empty { font-size: 10px; fill: var(--el-text-color-placeholder); }
</style>
