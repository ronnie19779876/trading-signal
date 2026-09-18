<script setup lang="ts">
/** 统计卡：大数字 + 说明 + 小字。此前在四个页面里抄了十二遍。 */
defineProps<{ value?: string | number; label?: string; sub?: string; valueClass?: string }>()
</script>

<template>
  <el-card shadow="never" class="stat">
    <div class="stat__v" :class="valueClass"><slot name="value">{{ value }}</slot></div>
    <div class="stat__l"><slot name="label">{{ label }}</slot></div>
    <div class="stat__s"><slot name="sub">{{ sub }}</slot></div>
  </el-card>
</template>

<style scoped>
.stat {
  height: 100%;
}
.stat__v {
  font-size: 22px;
  font-weight: 600;
  line-height: 1.3;
}
.stat__l {
  color: var(--el-text-color-regular);
  font-size: 13px;
  margin-top: 2px;
}
.stat__s {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-top: 4px;
  min-height: 16px;
}
</style>
