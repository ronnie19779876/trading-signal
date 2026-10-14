import { computed, onBeforeUnmount, ref } from 'vue'
import { errMsg } from '../lib/format'

/** 一块数据的取数：成功时自己把结果写进页面的 ref；失败就抛出，页面上那块保留上一次成功的数据。 */
export type Task = () => Promise<unknown>
/**
 * 一轮加载分几段：同一段的任务并发，下一段在上一段全部结束（无论成败）后开始，可以读上一段刚写好的数据
 * （例如先取池，再按池取日 K）。键是这块数据的中文名，失败时原样显示给用户。
 */
export type Stages = Array<Record<string, Task>>

/** 失败后的重试间隔（秒）：10、20、40，之后每 60 秒一次，直到全部成功 */
export const BACKOFF_SECONDS = [10, 20, 40, 60]

/**
 * 日级数据的加载与自动恢复（3.4.3，仪表盘、账户、持仓共用）。
 *
 * - **各块独立**：一块失败不影响其他块（原先 Promise.all 一个超时整批作废，净值、池一起显示成空，2026-10-13）；
 * - **自动恢复**：有失败就按 {@link BACKOFF_SECONDS} 退避重试，直到全部成功，提示自动消失；
 *   网络恢复（online 事件）时立刻补一轮；
 * - 平时每 refreshMs 刷新一轮。页面切到后台由页面调 stop()（与实时轮询一起停），回到前台再 start()，立刻补一轮。
 *
 * 同一时刻只跑一轮：上一轮没结束时再触发，直接复用那一轮。
 */
export function useRecoveringLoad(stages: () => Stages, refreshMs: number) {
  const failures = ref<Record<string, string>>({})
  /** 至少跑完过一轮（无论成败） */
  const loaded = ref(false)
  /** 距下一次自动重试的秒数；没有待重试的为 null */
  const retryIn = ref<number | null>(null)
  /** 正在取数（一轮进行中） */
  const busy = ref(false)

  let active = false
  let attempt = 0
  let inflight: Promise<void> | null = null
  let refreshTimer = 0
  let retryTimer = 0
  let countdownTimer = 0

  function clearRetry() {
    window.clearTimeout(retryTimer)
    window.clearInterval(countdownTimer)
    retryTimer = countdownTimer = 0
    retryIn.value = null
  }

  function scheduleRetry() {
    clearRetry()
    if (!active) return
    const seconds = BACKOFF_SECONDS[Math.min(attempt, BACKOFF_SECONDS.length - 1)]
    attempt++
    retryIn.value = seconds
    countdownTimer = window.setInterval(() => {
      if (retryIn.value !== null && retryIn.value > 1) retryIn.value--
    }, 1_000)
    retryTimer = window.setTimeout(() => {
      clearRetry()
      void run()
    }, seconds * 1_000)
  }

  /** 每块一结束就更新自己的状态：成功的立刻从提示里去掉，不必等同一轮里最慢的那块（开发库上 /api/pool 要 20~30 秒） */
  function settle(name: string, error: unknown | null) {
    const next = { ...failures.value }
    if (error === null) delete next[name]
    else next[name] = errMsg(error)
    failures.value = next
  }

  async function runOnce() {
    busy.value = true
    const failed = new Set<string>()
    try {
      for (const stage of stages()) {
        await Promise.all(Object.entries(stage).map(([name, task]) => task().then(
          () => settle(name, null),
          (e) => { failed.add(name); settle(name, e) },
        )))
      }
    } finally {
      busy.value = false
    }
    loaded.value = true
    if (failed.size) {
      scheduleRetry()
    } else {
      attempt = 0
      clearRetry()
    }
  }

  function run(): Promise<void> {
    if (!inflight) {
      inflight = runOnce().finally(() => {
        inflight = null
      })
    }
    return inflight
  }

  /** 立即重试（「立即重试」与「刷新」按钮、网络恢复时用）：退避计数不清零，失败了继续往后退 */
  function retryNow(): Promise<void> {
    clearRetry()
    return run()
  }

  const onOnline = () => {
    if (active && Object.keys(failures.value).length) void retryNow()
  }

  function start() {
    if (active) return
    active = true
    void run()
    refreshTimer = window.setInterval(() => {
      if (!retryTimer) void run()   // 有待重试的就交给重试，不叠一轮
    }, refreshMs)
    window.addEventListener('online', onOnline)
  }

  function stop() {
    active = false
    window.clearInterval(refreshTimer)
    refreshTimer = 0
    clearRetry()
    window.removeEventListener('online', onOnline)
  }

  onBeforeUnmount(stop)

  const failedNames = computed(() => new Set(Object.keys(failures.value)))
  /** 顶部提示：相同的错误合并成一条（「最新快照、池：timeout…；评估：…」），全部成功为 null */
  const errorText = computed(() => {
    const byMessage = new Map<string, string[]>()
    for (const [name, message] of Object.entries(failures.value)) {
      byMessage.set(message, [...(byMessage.get(message) ?? []), name])
    }
    return byMessage.size ? [...byMessage].map(([message, names]) => `${names.join('、')}：${message}`).join('；') : null
  })

  return { loaded, busy, failures, failedNames, errorText, retryIn, run, retryNow, start, stop }
}
