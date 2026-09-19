<script setup lang="ts">
import { SESSION_LABEL, useMarketClock } from '../composables/useMarketClock'

/**
 * 页头的美东时段灯：盘中绿色呼吸、盘前盘后蓝色、休市灰色。
 * 做成独立组件是为了把每秒一次的走时关在这里，不让整个页面每秒重渲染。
 */
const { et, session, nextOpen, halfDay } = useMarketClock()
</script>

<template>
  <span class="clock" :class="'clock--' + session">
    <span class="clock__led" />
    <b>{{ SESSION_LABEL[session] }}</b>
    <i v-if="halfDay && session !== 'CLOSED'" class="clock__half">半日 · 13:00 收</i>
    <span class="clock__sep">·</span>
    <span class="num">美东 {{ et.clock }}</span>
    <template v-if="session !== 'REGULAR'">
      <span class="clock__sep">·</span>
      <span>{{ nextOpen }} 开盘</span>
    </template>
  </span>
</template>

<style scoped>
.clock {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}
.clock b {
  font-weight: 600;
}
.clock__sep {
  color: var(--el-border-color);
}
.clock__half {
  font-style: normal;
  font-size: 10px;
  color: var(--el-color-warning);
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 4px;
  padding: 0 4px;
}
.clock__led {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--el-text-color-placeholder);
  flex: none;
}
.clock--REGULAR .clock__led {
  background: var(--tr-up);
  animation: pulse 2s ease-in-out infinite;
}
.clock--REGULAR b {
  color: var(--tr-up);
}
.clock--PRE .clock__led,
.clock--AFTER .clock__led {
  background: var(--el-color-primary);
}
.clock--PRE b,
.clock--AFTER b {
  color: var(--el-color-primary);
}
@keyframes pulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 var(--tr-up-fill);
  }
  70% {
    box-shadow: 0 0 0 6px transparent;
  }
}
@media (prefers-reduced-motion: reduce) {
  .clock--REGULAR .clock__led {
    animation: none;
  }
}
</style>
