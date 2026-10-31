<script setup lang="ts">
/**
 * Alert for failed loads (3.4.3): says which blocks failed and in how many seconds it retries automatically, with a
 * "retry now" button. Once everything succeeds the page sets error to null and the alert disappears by itself.
 */
defineProps<{ error: string | null; retryIn: number | null; busy?: boolean }>()
const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <el-alert v-if="error" type="error" show-icon :closable="false">
    <template #title>
      部分数据取数失败（{{ error }}）<template v-if="busy">，正在重试…</template><template v-else-if="retryIn !== null">，{{ retryIn }} 秒后自动重试</template>；其余部分照常显示，失败的部分保留上一次的数据
      <el-button v-if="!busy" link type="primary" size="small" class="retry" @click="emit('retry')">立即重试</el-button>
    </template>
  </el-alert>
</template>

<style scoped>
.retry { margin-left: 8px; vertical-align: baseline; }
</style>
