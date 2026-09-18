<script setup lang="ts">
/** 页头：标题一行（操作按钮跟在标题右边），说明文字另起一行。五个页面此前各写一套，对齐方式与字号都不一样。 */
defineProps<{ title: string; hint?: string }>()
</script>

<template>
  <div class="page-header">
    <div class="page-header__row">
      <h2>{{ title }}</h2>
      <slot name="actions" />
    </div>
    <div v-if="hint || $slots.hint" class="muted page-header__hint">
      <slot name="hint">{{ hint }}</slot>
    </div>
  </div>
</template>

<style scoped>
.page-header__row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.page-header__row h2 {
  margin: 0;
  font-size: 18px;
}
.page-header__hint {
  margin-top: 6px;
  line-height: 1.6;
}
</style>
