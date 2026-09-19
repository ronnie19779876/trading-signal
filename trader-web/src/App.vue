<script setup lang="ts">
import { computed } from 'vue'
import { useAppStore } from './stores/app'
import { useAutoRefresh } from './composables/useAutoRefresh'
import { useTheme, type ThemeMode } from './composables/useTheme'
import MarketClock from './components/MarketClock.vue'
import { jobLabel } from './lib/system'

const links = [
  { to: '/', label: '仪表盘' },
  { to: '/positions', label: '持仓' },
  { to: '/account', label: '账户' },
  { to: '/fundamentals', label: '基本面' },
  { to: '/signals', label: '入场信号' },
  { to: '/trades', label: '交易' },
  { to: '/system', label: '系统' },
]

/**
 * 绑定成普通 URL，让浏览器直接取 public/favicon.svg。写成静态 src 时 Vite 会把它内联成 base64，
 * 开发时改了 SVG 页头还是旧图（资源缓存不跟着 public 文件失效，2026-09-19 实测）。
 */
const LOGO = '/favicon.svg'

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
        <img :src="LOGO" alt="" width="26" height="26" />
        <span>T-Signal</span>
      </router-link>

      <nav class="shell__nav">
        <router-link v-for="l in links" :key="l.to" :to="l.to">{{ l.label }}</router-link>
      </nav>

      <span class="shell__spacer"></span>

      <MarketClock />

      <!-- 生产实例红色标记：写操作按钮长得一样，别对着生产点。 -->
      <el-tag v-if="env" size="small" :type="env === 'PROD' ? 'danger' : 'success'" effect="plain">{{ env }}</el-tag>
      <router-link v-if="app.running" :to="{ path: '/system', query: { tab: 'jobs' } }" class="shell__job">
        <el-tag size="small" type="warning" effect="plain">
          作业 #{{ app.running.id }} {{ jobLabel(app.running.job) }} 运行中
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
  margin-right: 8px;
  font-weight: 600;
  font-size: 14px;
  color: var(--el-text-color-primary);
  text-decoration: none;
}
/* 菜单：按钮效果（参考 futu-trader）——每项圆角块，悬停出底色，选中淡蓝底蓝字 */
.shell__nav {
  display: flex;
  gap: 2px;
}
.shell__nav a {
  padding: 5px 12px;
  border-radius: 6px;
  font-size: 13px;
  line-height: 18px;
  color: var(--el-text-color-regular);
  text-decoration: none;
  transition: background-color 0.15s ease, color 0.15s ease;
}
.shell__nav a:hover {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
}
/* 用 exact-active：'/' 是所有路由的前缀，router-link-active 会让"仪表盘"永远高亮。 */
.shell__nav a.router-link-exact-active {
  background: var(--el-color-primary-light-9);
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
