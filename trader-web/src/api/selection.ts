import { http } from './http'
import type { AuditReport } from './account'

/** 与 docs/API.md「量化选股（第 6 期）」一一对应。只读：回补、重放、重算不在页面上做。 */

export type Rating = 'STRONG_BUY' | 'BUY' | 'HOLD' | 'SELL' | 'STRONG_SELL'
export type Exclusion = 'SECURITY_TYPE' | 'NO_SECTOR' | 'SECTOR_TOO_SMALL' | 'PRICE_BELOW_MIN' | 'NO_PRICE' | 'INSUFFICIENT_HISTORY'
export type Metric =
  | 'EARNINGS_YIELD' | 'BOOK_YIELD' | 'SALES_YIELD'
  | 'REVENUE_GROWTH' | 'EPS_GROWTH'
  | 'GROSS_MARGIN' | 'ROE' | 'FCF_MARGIN'
  | 'MOMENTUM_3M' | 'MOMENTUM_6M' | 'MOMENTUM_12M'

export interface ScoreView {
  tradeDate: string
  instrumentId: number
  symbol: string
  sector: string | null
  exclusion: Exclusion | null
  exclusionDetail: string | null
  valueScore: number | null
  growthScore: number | null
  profitScore: number | null
  momentumScore: number | null
  composite: number | null
  compositePct: number | null
  rating: Rating | null
  sbStreak: number
  /** JSON 文本：{指标: [原值, 板块内百分位]}，见 parseMetrics */
  metrics: string
  fingerprint: string
  computedAt: string
}

export interface StateRow {
  instrumentId: number
  symbol: string
  part: string
  status: string
  detail: string | null
  updatedAt: string
}

export interface InputsReport {
  asOf: string
  targets: number
  parts: Record<string, { byStatus: Record<string, number>; missing: number }>
  failed: StateRow[]
  empty: StateRow[]
  priceBars: { rows: number; instruments: number; earliest: string | null; latest: string | null }
  seams: { checked: number; ok: number }
  valuation: { rows: number; instruments: number; earliest: string | null; latest: string | null; pe: number; pb: number; ps: number }
  valuationVsSnapshot: { compared: number; peMismatch: number; pbMismatch: number; samples: unknown[] }
  earnings: { reports: number; matched: number; beyondRule: number; medianLagDays: number | null; unmatchedSamples: string[]; beyondRuleSamples: string[] }
  financials: { instruments: number; deep: number; medianEarliest: string | null; shallowSamples: string[] }
}

export const selectionApi = {
  days: async (limit = 400) => (await http.get<string[]>('/api/selection/days', { params: { limit } })).data,
  scores: async (date?: string) => (await http.get<ScoreView[]>('/api/selection/scores', { params: date ? { date } : {} })).data,
  history: async (symbol: string, from?: string, to?: string) =>
    (await http.get<ScoreView[]>(`/api/selection/scores/${encodeURIComponent(symbol)}`, { params: { from, to } })).data,
  audit: async (date?: string) => (await http.get<AuditReport>('/api/selection/audit', { params: date ? { date } : {} })).data,
  // 输入覆盖报告要扫几张大表（生产约 4 秒、开发实例经隧道更久）
  inputs: async () => (await http.get<InputsReport>('/api/selection/inputs', { timeout: 60_000 })).data,
}
