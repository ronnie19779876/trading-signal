<script setup lang="ts">
const links = [{ to: '/', label: '系统' }]
</script>

<template>
  <el-container class="shell">
    <el-header class="shell__header">
      <span class="shell__brand">trading-signal</span>
      <nav class="shell__nav">
        <router-link v-for="l in links" :key="l.to" :to="l.to">{{ l.label }}</router-link>
      </nav>
    </el-header>
    <el-main class="shell__main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.shell {
  min-height: 100vh;
}
.shell__header {
  display: flex;
  align-items: center;
  gap: 24px;
  border-bottom: 1px solid var(--el-border-color);
  background: var(--el-bg-color);
}
.shell__brand {
  font-weight: 600;
  font-size: 16px;
}
.shell__nav a {
  color: var(--el-text-color-regular);
  text-decoration: none;
  margin-right: 16px;
}
.shell__nav a.router-link-active {
  color: var(--el-color-primary);
}
.shell__main {
  max-width: 1100px;
  width: 100%;
  margin: 0 auto;
}
</style>
