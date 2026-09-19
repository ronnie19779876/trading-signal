<script setup lang="ts">
import { computed } from 'vue'

/**
 * 迷你走势（纯 SVG）。一行一个 canvas 图表太重，这里不需要交互。
 * 颜色按首尾比较取涨跌色（tokens.css，美股口径绿涨红跌）。
 */
const props = withDefaults(defineProps<{ points: number[]; width?: number; height?: number }>(), { width: 96, height: 26 })

const path = computed(() => {
  const p = props.points
  if (p.length < 2) return ''
  const min = Math.min(...p)
  const span = Math.max(...p) - min || 1 // 全平时避免除零
  const dx = props.width / (p.length - 1)
  const h = props.height - 4 // 上下各留 2px，极值点不贴边
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
