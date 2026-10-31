import { ref } from 'vue'
import { accountApi, type LiveView } from '../api/account'
import { useMarketClock } from './useMarketClock'

/**
 * Polling of the IBKR live account (shared by the dashboard, positions and account pages). The backend only reads
 * memory and never calls the gateway; the first read starts the subscription, and it unsubscribes automatically
 * after 5 minutes without readers.
 *
 * Interval: 1 s during the regular session (prices and per-position P&L are moving; close to the refresh feel of
 * the IBKR app); 1.5 s right after subscribing while data is still arriving; 5 s pre-/after-market and when
 * closed. The account summary (NAV, cash) is pushed by IBKR itself roughly every 3 minutes, so faster polling
 * cannot make it update sooner.
 * The next poll is scheduled only after the previous one returns, so a slow endpoint never stacks requests. The
 * page calls stop() when it goes to the background.
 *
 * `running` is the run guard for the self-rescheduling setTimeout chain: stop() during an in-flight request clears
 * the flag, so the callback's schedule() returns instead of re-arming the chain. schedule() also clears the single
 * timer handle first, so a quick stop()/start() never leaves two chains running.
 */
export function useLiveAccount() {
  const live = ref<LiveView | null>(null)
  const { session } = useMarketClock()
  let timer = 0
  let running = false

  async function load() {
    try {
      live.value = await accountApi.live()
    } catch {
      live.value = null // endpoint unreachable: the page falls back to the closing snapshot
    }
  }

  function interval(): number {
    if (session.value === 'REGULAR') return 1_000
    return live.value?.status === 'WARMING' ? 1_500 : 5_000
  }

  function schedule() {
    window.clearTimeout(timer)
    if (!running) return
    timer = window.setTimeout(async () => {
      await load()
      schedule()
    }, interval())
  }

  function start() {
    if (running) return
    running = true
    void load().then(schedule)
  }

  function stop() {
    running = false
    window.clearTimeout(timer)
    timer = 0
  }

  return { live, start, stop }
}
