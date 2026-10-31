<script setup lang="ts">
/**
 * Page header: the title on one line (action buttons to its right), the description on the next line. Five pages
 * used to have their own versions with different alignment and font sizes.
 */
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
