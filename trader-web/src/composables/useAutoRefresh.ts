import { onBeforeUnmount, onMounted } from 'vue'

/**
 * Periodic refresh. Four pages used to have their own setInterval with different rules (the fundamentals page
 * ignored visibility and kept hitting the backend every 5 seconds from a background tab).
 *
 * Common rule: skip the tick while the page is hidden; when it becomes visible again run once immediately instead
 * of waiting for the next period. The timer is created on mount and cleared on unmount, so it cannot leak.
 * Note there is no in-flight guard: `fn` is called on every tick even if the previous call has not finished.
 */
export function useAutoRefresh(fn: () => unknown, ms: number, options: { immediate?: boolean } = {}) {
  let timer: ReturnType<typeof setInterval> | null = null

  const tick = () => {
    if (document.visibilityState === 'visible') fn()
  }

  const onVisible = () => {
    if (document.visibilityState === 'visible') fn()
  }

  onMounted(() => {
    if (options.immediate !== false) fn()
    timer = setInterval(tick, ms)
    document.addEventListener('visibilitychange', onVisible)
  })

  onBeforeUnmount(() => {
    if (timer) clearInterval(timer)
    timer = null
    document.removeEventListener('visibilitychange', onVisible)
  })
}
