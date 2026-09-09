import { http } from './http'

export type MarketSession = 'PRE' | 'RTH' | 'AFTER' | 'OVERNIGHT' | 'CLOSED'

export interface SessionQuote {
  price: number | null
  change: number | null
  changeRate: number | null
  volume: number
}

export interface Quote {
  instrument: { market: string; symbol: string }
  session: MarketSession
  price: number | null
  change: number | null
  changeRate: number | null
  open: number | null
  high: number | null
  low: number | null
  rthPrice: number | null
  /** 券商给的"昨收"：常规时段的前一交易日收盘；盘前盘后它不随时段推进，别拿来算涨跌 */
  lastClose: number | null
  /** change / changeRate 实际用的基准：常规时段与收市为 lastClose，盘前盘后夜盘为 rthPrice。展示"参考价"用这个 */
  referenceClose: number | null
  volume: number
  turnover: number | null
  preMarket: SessionQuote | null
  afterMarket: SessionQuote | null
  overnight: SessionQuote | null
  quoteTime: string | null
  receivedAt: string
  suspended: boolean
}

export interface QuoteStatus {
  enabled: boolean
  paused: boolean
  desired: number
  subscribed: number
  deferredUnsubscribe: number
  quota: { usedQuota: number; remainQuota: number; byType: Record<string, number>; checkedAt: string } | null
  lastReconcileAt: string | null
  lastError: string | null
  cached: number
  totalPushes: number
  pushesLastMinute: number
  lastPushAt: string | null
  streamClients: number
}

export const getQuotes = async () => (await http.get<Quote[]>('/api/quotes')).data
export const getQuoteStatus = async () => (await http.get<QuoteStatus>('/api/quotes/status')).data
export const reconcileQuotes = async () => (await http.post('/api/quotes/subscriptions/reconcile')).data
export const pauseQuotes = async () => (await http.post('/api/quotes/subscriptions/pause')).data
export const resumeQuotes = async () => (await http.post('/api/quotes/subscriptions/resume')).data
export const quoteStreamUrl = '/api/quotes/stream'
