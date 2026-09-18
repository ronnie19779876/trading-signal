import { computed, ref, watchEffect } from 'vue'

export type ThemeMode = 'system' | 'light' | 'dark'

const KEY = 'trader.theme'
const MODES: ThemeMode[] = ['system', 'light', 'dark']

function stored(): ThemeMode {
  try {
    const v = localStorage.getItem(KEY)
    return MODES.includes(v as ThemeMode) ? (v as ThemeMode) : 'system'
  } catch {
    return 'system'   // 隐私模式下 localStorage 会抛
  }
}

const media = window.matchMedia('(prefers-color-scheme: dark)')

// 模块级单例：页头的切换按钮、各页面、图表组件读的是同一份状态。
const mode = ref<ThemeMode>(stored())
const systemDark = ref(media.matches)
media.addEventListener('change', (e) => (systemDark.value = e.matches))

const isDark = computed(() => (mode.value === 'system' ? systemDark.value : mode.value === 'dark'))

// Element Plus 的暗色靠 <html class="dark"> 打开；图表组件 watch isDark 重建配色。
watchEffect(() => document.documentElement.classList.toggle('dark', isDark.value))

export function useTheme() {
  function setMode(m: ThemeMode) {
    mode.value = m
    try {
      localStorage.setItem(KEY, m)
    } catch {
      // 存不下就只在本次会话生效
    }
  }

  return { mode, isDark, setMode }
}
