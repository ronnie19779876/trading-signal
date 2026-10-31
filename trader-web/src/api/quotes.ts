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
  /** The broker's "previous close": the prior trading day's regular-session close. It does not advance pre-/after-market, so do not compute changes from it */
  lastClose: number | null
  /** The base change / changeRate are actually computed against: lastClose during the regular session and when closed, rthPrice pre-/after-market and overnight. Use this to display the "reference price" */
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
