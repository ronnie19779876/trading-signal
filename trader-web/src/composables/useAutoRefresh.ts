import { onBeforeUnmount, onMounted } from 'vue'

/**
 * 定时刷新。此前四个页面各写一份 setInterval，规则还不一样
 * （基本面页不看可见性，切到后台标签页照样每 5 秒打后端）。
 *
 * 统一规则：页面不可见时跳过这一拍；重新可见时立刻补一次，不用等下一个周期。
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
