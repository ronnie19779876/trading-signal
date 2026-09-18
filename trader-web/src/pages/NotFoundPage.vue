<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <div class="page">
    <el-result icon="warning" title="找不到这个页面">
      <template #sub-title>
        <span class="muted">{{ route.fullPath }}</span>
      </template>
      <template #extra>
        <el-button type="primary" @click="$router.push('/')">回首页</el-button>
      </template>
    </el-result>
  </div>
</template>
