<script setup lang="ts">
import { computed } from 'vue'
import { useAppStore } from './stores/app'
import { useAutoRefresh } from './composables/useAutoRefresh'
import { useTheme, type ThemeMode } from './composables/useTheme'

const links = [
  { to: '/', label: '系统' },
  { to: '/marketdata', label: '行情' },
  { to: '/fundamentals', label: '基本面' },
  { to: '/account', label: '账户' },
  { to: '/signals', label: '信号' },
]

const THEME_LABEL: Record<ThemeMode, string> = { system: '跟随系统', light: '浅色', dark: '深色' }

const app = useAppStore()
// 页头常驻显示环境与当前作业，各页面不必再自己轮询这两样。
useAutoRefresh(() => app.refresh(), 10_000)

const { mode, isDark, setMode } = useTheme()
const env = computed(() => app.info?.environment ?? null)
</script>

<template>
  <div class="shell">
    <header class="shell__header">
      <router-link to="/" class="shell__brand">
        <img src="/favicon.svg" alt="" width="20" height="20" />
        <span>T-Signal</span>
      </router-link>

      <nav class="shell__nav">
        <router-link v-for="l in links" :key="l.to" :to="l.to">{{ l.label }}</router-link>
      </nav>

      <span class="shell__spacer"></span>

      <!-- 生产实例红色标记：写操作按钮长得一样，别对着生产点。 -->
      <el-tag v-if="env" size="small" :type="env === 'PROD' ? 'danger' : 'success'" effect="plain">{{ env }}</el-tag>
      <router-link v-if="app.running" to="/marketdata" class="shell__job">
        <el-tag size="small" type="warning" effect="plain">
          作业 #{{ app.running.id }} {{ app.running.job }} 运行中
        </el-tag>
      </router-link>

      <el-dropdown trigger="click" @command="setMode">
        <el-button size="small" text :title="'主题：' + THEME_LABEL[mode]">
          <span class="shell__theme">{{ isDark ? '☾' : '☀' }}</span>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-for="(label, k) in THEME_LABEL" :key="k" :command="k" :disabled="mode === k">
              {{ label }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </header>

    <main class="shell__main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.shell {
  min-height: 100vh;
  background: var(--el-bg-color-page);
}
.shell__header {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 16px;
  height: 52px;
  padding: 0 var(--tr-gutter);
  border-bottom: 1px solid var(--el-border-color);
  background: var(--el-bg-color);
}
.shell__brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 16px;
  color: var(--el-text-color-primary);
  text-decoration: none;
}
.shell__nav {
  display: flex;
  gap: 16px;
}
.shell__nav a {
  color: var(--el-text-color-regular);
  text-decoration: none;
}
/* 用 exact-active：'/' 是所有路由的前缀，router-link-active 会让"系统"永远高亮。 */
.shell__nav a.router-link-exact-active {
  color: var(--el-color-primary);
  font-weight: 600;
}
.shell__spacer {
  flex: 1;
}
.shell__job {
  text-decoration: none;
}
.shell__theme {
  font-size: 15px;
  line-height: 1;
}
/* 满宽：窗口最大化时 1100px 的老上限会让两侧各空掉几百像素，表格却在里面横向滚动。 */
.shell__main {
  width: 100%;
  padding: 16px var(--tr-gutter) 32px;
  box-sizing: border-box;
}
</style>
