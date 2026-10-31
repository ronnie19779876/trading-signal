import { computed, ref, watchEffect } from 'vue'

export type ThemeMode = 'system' | 'light' | 'dark'

const KEY = 'trader.theme'
const MODES: ThemeMode[] = ['system', 'light', 'dark']

function stored(): ThemeMode {
  try {
    const v = localStorage.getItem(KEY)
    return MODES.includes(v as ThemeMode) ? (v as ThemeMode) : 'system'
  } catch {
    return 'system'   // localStorage throws in private mode
  }
}

const media = window.matchMedia('(prefers-color-scheme: dark)')

// Module-level singleton: the header toggle, the pages and the chart components all read the same state.
const mode = ref<ThemeMode>(stored())
const systemDark = ref(media.matches)
media.addEventListener('change', (e) => (systemDark.value = e.matches))

const isDark = computed(() => (mode.value === 'system' ? systemDark.value : mode.value === 'dark'))

// Element Plus dark mode is switched on by <html class="dark">; chart components watch isDark and rebuild their colors.
watchEffect(() => document.documentElement.classList.toggle('dark', isDark.value))

export function useTheme() {
  function setMode(m: ThemeMode) {
    mode.value = m
    try {
      localStorage.setItem(KEY, m)
    } catch {
      // if it cannot be stored it only applies to this session
    }
  }

  return { mode, isDark, setMode }
}
