import { http } from './http'
import type { AuditReport } from './account'

/**
 * 与 docs/API.md「量化选股（第 6 期）」一一对应。评分部分只读（回补、重放不在页面上做）；
 * 模拟交易（§26）的加入与平仓是页面上唯一的写操作。
 */

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

// ---------------------------------------------------------------- 模拟交易（ARCHITECTURE §26）

export type SimStatus = 'PENDING_ENTRY' | 'OPEN' | 'CLOSED' | 'CANCELLED'
export type SimExitReason = 'STOP' | 'CHANDELIER' | 'TAKE_PROFIT' | 'MANUAL'

/** 一笔模拟交易；价格都是评分日口径（持有期拆股时价与股数同比换算，金额不变）。比例为小数。 */
export interface SimTrade {
  id: number
  rulesVersion: string
  instrumentId: number
  symbol: string
  modelVersion: string
  scoreDate: string
  scoreRank: number
  rating: Rating | null
  composite: number | null
  requestedAt: string
  setupClose: number
  setupAtr: number
  formulaStop: number
  stop: number
  stopLeg: 'ATR' | 'ZONE' | 'CAP'
  resistance: number | null
  plannedEntryDate: string
  status: SimStatus
  entryDate: string | null
  entryPrice: number | null
  shares: number | null
  plusOneR: number | null
  target: number | null
  targetSource: 'RESISTANCE' | 'FALLBACK_2R' | null
  touchedPlusOneR: boolean
  targetHitDate: string | null
  activeStop: number | null
  chandelierActive: boolean
  closeRequestedAt: string | null
  plannedExitDate: string | null
  closeKind: 'TAKE_PROFIT' | 'MANUAL' | null
  closeNote: string | null
  exitDate: string | null
  exitPrice: number | null
  exitReason: SimExitReason | null
  pnl: number | null
  returnPct: number | null
  rMultiple: number | null
  mfeR: number | null
  maeR: number | null
  barsHeld: number | null
  lastClose: number | null
  lastCloseDate: string | null
  unrealizedPnl: number | null
  spyReturn: number | null
  excessReturn: number | null
  shadowStatus: 'OPEN' | 'CLOSED' | null
  shadowExitDate: string | null
  shadowExitPrice: number | null
  shadowExitReason: 'STOP' | 'CHANDELIER' | null
  shadowPnl: number | null
  shadowReturn: number | null
  shadowLastClose: number | null
  cancelReason: string | null
  updatedThrough: string | null
  updatedAt: string
}

export interface SimCandidate {
  rank: number
  instrumentId: number
  symbol: string
  rating: Rating | null
  composite: number | null
  /** AVAILABLE 可加入；ACTIVE 已在池里；FULL 池满 */
  state: 'AVAILABLE' | 'ACTIVE' | 'FULL'
  tradeId: number | null
}

export interface SimOverview {
  rules: Record<string, string | number>
  account: { rulesVersion: string; initialCapital: number; createdAt: string }
  startDate: string | null
  cash: number
  positionsValue: number
  equity: number
  realized: number
  unrealized: number
  totalReturn: number | null
  spySinceStart: number | null
  markedThrough: string | null
  open: number
  pending: number
  closed: number
  cancelled: number
  targetAlerts: number
  candidates: { scoreDate: string | null; active: number; slots: number; top: SimCandidate[] }
}

export interface SimPreview {
  symbol: string
  rank: number
  scoreDate: string
  rating: Rating | null
  composite: number | null
  setup: { setupDate: string; close: number; atr: number; formulaStop: number; stop: number; leg: 'ATR' | 'ZONE' | 'CAP'; resistance: number | null }
  plannedEntryDate: string
  estimatedNotional: number
  estimatedShares: number
  cashNow: number
}

export interface SimEvent {
  id: number
  tradeId: number | null
  symbol: string | null
  at: string
  kind: string
  detail: string
}

export interface SimEquityRow {
  tradeDate: string
  cash: number
  positionsValue: number
  equity: number
  openPositions: number
}

export interface SimWeeklyTrade {
  id: number
  symbol: string
  entryDate: string
  entry: number
  shares: number
  exitDate: string | null
  exit: number | null
  reason: SimExitReason | null
  pnl: number | null
  returnPct: number | null
  rMultiple: number | null
  spyReturn: number | null
  excessReturn: number | null
  shadowStatus: 'OPEN' | 'CLOSED' | null
  shadowPnl: number | null
  shadowReturn: number | null
}

export interface SimWeeklyOpen {
  id: number
  symbol: string
  entryDate: string
  entry: number
  shares: number
  markDate: string | null
  mark: number | null
  unrealizedPnl: number | null
  returnPct: number | null
  spyReturn: number | null
  excessReturn: number | null
  targetHitDate: string | null
}

export interface SimWeeklyReport {
  rulesVersion: string
  weekStart: string
  weekEnd: string
  accountStart: string
  initialCapital: number
  equityStart: number
  equityEnd: number
  cashEnd: number
  weekPnl: number
  weekReturn: number | null
  cumulativeReturn: number | null
  realizedWeek: number
  unrealizedEnd: number
  openAtEnd: number
  spyWeekReturn: number | null
  spySinceStart: number | null
  universeWeekReturn: number | null
  universeCount: number
  maxDrawdown: number | null
  closedThisWeek: SimWeeklyTrade[]
  allClosed: {
    n: number; wins: number; losses: number; pnl: number; meanReturn: number | null; meanExcess: number | null; withExcess: number
    manualClosed: number; manualMinusShadow: number | null; manualPaired: number
  }
  openPositions: SimWeeklyOpen[]
  generatedThrough: string
  caveats: string[]
}

export interface SimWeekly {
  weekEnd: string
  weekStart: string
  rulesVersion: string
  equityEnd: number
  weekReturn: number | null
  spyWeekReturn: number | null
  closedTrades: number
  generatedAt: string
  /** 列表接口不带正文（null），单周接口带 */
  report: SimWeeklyReport | null
}

export const simApi = {
  overview: async () => (await http.get<SimOverview>('/api/selection/sim')).data,
  preview: async (symbol: string) => (await http.get<SimPreview>('/api/selection/sim/preview', { params: { symbol } })).data,
  add: async (symbol: string) => (await http.post<SimTrade>('/api/selection/sim/trades', null, { params: { symbol } })).data,
  close: async (id: number, note?: string) =>
    (await http.post<SimTrade>(`/api/selection/sim/trades/${id}/close`, null, { params: note ? { note } : {} })).data,
  trades: async (status?: SimStatus) => (await http.get<SimTrade[]>('/api/selection/sim/trades', { params: status ? { status } : {} })).data,
  trade: async (id: number) => (await http.get<{ trade: SimTrade; events: SimEvent[] }>(`/api/selection/sim/trades/${id}`)).data,
  events: async (limit = 100) => (await http.get<SimEvent[]>('/api/selection/sim/events', { params: { limit } })).data,
  equity: async (from?: string, to?: string) => (await http.get<SimEquityRow[]>('/api/selection/sim/equity', { params: { from, to } })).data,
  weeklies: async (limit = 52) => (await http.get<SimWeekly[]>('/api/selection/sim/weekly', { params: { limit } })).data,
  weekly: async (weekEnd: string) => (await http.get<SimWeekly>(`/api/selection/sim/weekly/${weekEnd}`)).data,
}
