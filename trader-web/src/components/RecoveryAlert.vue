<script setup lang="ts">
/**
 * 取数失败的提示（3.4.3）：写明哪几块失败、几秒后自动重试，带「立即重试」。全部成功后由页面把 error 置空，提示自动消失。
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
