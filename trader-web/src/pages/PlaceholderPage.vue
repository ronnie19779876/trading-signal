<script setup lang="ts">
import { useRoute } from 'vue-router'

/** Placeholder for menu entries whose page is not built yet (menu reorganized on 2026-09-19, pages done one by one). */
const route = useRoute()
</script>

<template>
  <div class="page">
    <el-result icon="info" :title="`${route.meta.title ?? ''}：页面待设计`">
      <template #sub-title>
        <span class="muted">按"一页一页来"的顺序，做完仪表盘后再设计这一页。</span>
      </template>
    </el-result>
  </div>
</template>
