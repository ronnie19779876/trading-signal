import { onBeforeUnmount, onMounted } from 'vue'

/**
 * Periodic refresh. Four pages used to have their own setInterval with different rules (the fundamentals page
 * ignored visibility and kept hitting the backend every 5 seconds from a background tab).
 *
 * Common rule: skip the tick while the page is hidden; when it becomes visible again run once immediately instead
 * of waiting for the next period; skip the tick while the previous call has not returned. The timer is created on
 * mount and cleared on unmount, so it cannot leak.
 *
 * Single flight: while the previous call's promise is pending, ticks, visibility changes and the immediate call are
 * skipped. Without it a request slower than the period (a tunnel hiccup is 10 s against a 5 s period) piles up a
 * new request every tick and the responses race each other (found in the 2026-10-31 review). Manual refresh
 * buttons call fn directly and are not throttled.
 */
export function useAutoRefresh(fn: () => unknown, ms: number, options: { immediate?: boolean } = {}) {
  let timer: ReturnType<typeof setInterval> | null = null
  let pending = false

  const run = () => {
    if (pending) return
    const r = fn()
    if (r instanceof Promise) {
      pending = true
      const done = () => { pending = false }
      // both handlers: a rejected call must release the guard without becoming an unhandled rejection here
      r.then(done, done)
    }
  }

  const tick = () => {
    if (document.visibilityState === 'visible') run()
  }

  const onVisible = () => {
    if (document.visibilityState === 'visible') run()
  }

  onMounted(() => {
    if (options.immediate !== false) run()
    timer = setInterval(tick, ms)
    document.addEventListener('visibilitychange', onVisible)
  })

  onBeforeUnmount(() => {
    if (timer) clearInterval(timer)
    timer = null
    document.removeEventListener('visibilitychange', onVisible)
  })
}
