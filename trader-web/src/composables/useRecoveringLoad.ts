import { computed, onBeforeUnmount, ref } from 'vue'
import { errMsg } from '../lib/format'

/**
 * Loading one block of data: on success the task writes the result into the page's ref itself; on failure it
 * throws, and that block of the page keeps the data from the last successful load.
 */
export type Task = () => Promise<unknown>
/**
 * One load round consists of stages: tasks in the same stage run concurrently; the next stage starts after every
 * task of the previous stage has settled (success or failure) and may read data the previous stage just wrote
 * (e.g. load the pool first, then the daily bars for the pool). Keys are the Chinese names of the blocks and are
 * shown to the user verbatim on failure.
 */
export type Stages = Array<Record<string, Task>>

/** Retry delays after a failure (seconds): 10, 20, 40, then every 60 seconds until everything succeeds */
export const BACKOFF_SECONDS = [10, 20, 40, 60]

/**
 * Loading and automatic recovery for daily-granularity data (3.4.3, shared by the dashboard, account and
 * positions pages).
 *
 * - **Blocks are independent**: one failing block does not affect the others (previously a single timeout inside
 *   a Promise.all discarded the whole batch and NAV and pool both showed as empty, 2026-10-13);
 * - **Automatic recovery**: on any failure it retries with the {@link BACKOFF_SECONDS} backoff until everything
 *   succeeds, and the alert disappears by itself; when the network comes back (`online` event) a round runs at once;
 * - Otherwise it refreshes one round every refreshMs. When the page goes to the background the page calls stop()
 *   (together with the live polling); back in the foreground start() runs a round immediately.
 *
 * Only one round runs at a time: a trigger while a round is still in flight reuses that round, so a slow response
 * can never be overwritten by an older one.
 */
export function useRecoveringLoad(stages: () => Stages, refreshMs: number) {
  const failures = ref<Record<string, string>>({})
  /** At least one round has completed (successfully or not) */
  const loaded = ref(false)
  /** Seconds until the next automatic retry; null when no retry is pending */
  const retryIn = ref<number | null>(null)
  /** Loading (a round is in progress) */
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

  /**
 * Each block updates its own status as soon as it settles: a success is removed from the alert immediately
 * instead of waiting for the slowest block of the round (/api/pool takes 20~30 s on the dev database).
 * The map is replaced rather than mutated so the computed views re-evaluate.
 */
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

  /**
 * Retry now (used by the "retry now" and "refresh" buttons and when the network comes back): the backoff counter
 * is not reset, so another failure keeps backing off further.
 */
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
      if (!retryTimer) void run()   // a pending retry takes precedence; do not stack another round on top
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
  /** The alert text: identical errors are merged into one entry ("block A, block B: timeout...; block C: ..."); null when everything succeeded */
  const errorText = computed(() => {
    const byMessage = new Map<string, string[]>()
    for (const [name, message] of Object.entries(failures.value)) {
      byMessage.set(message, [...(byMessage.get(message) ?? []), name])
    }
    return byMessage.size ? [...byMessage].map(([message, names]) => `${names.join('、')}：${message}`).join('；') : null
  })

  return { loaded, busy, failures, failedNames, errorText, retryIn, run, retryNow, start, stop }
}
