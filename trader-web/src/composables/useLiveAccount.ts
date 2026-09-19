import { ref } from 'vue'
import { accountApi, type LiveView } from '../api/account'
import { useMarketClock } from './useMarketClock'

/**
 * 盈透实时账户的轮询（仪表盘、持仓页、账户页共用）。后端只读内存、不打网关，第一次读发起订阅，5 分钟没人读自动退订。
 *
 * 间隔：常规交易时段 1 秒（价格与逐只盈亏在变，贴近盈透 App 的刷新感）；刚订阅、数据还没到齐时 1.5 秒；
 * 盘前盘后与休市 5 秒。账户汇总（净值、现金）是盈透自己约 3 分钟推一批，轮询再快也不会更新得更快。
 * 上一轮返回后才排下一轮，接口慢时不会叠请求。页面切到后台时由页面调用 stop()。
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
      live.value = null // 接口不可达：页面退回收盘快照
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
