import { http } from './http'

export interface InstrumentView {
  symbol: string
  name: string | null
  nameCn: string | null
  type: string
  resolveStatus: string
  delisted: boolean
  indexes: string[]
  sector: string | null
  subIndustry: string | null
  role: 'POOL' | 'HOLDING' | 'BENCHMARK' | null
  depth: string | null
  earliest: string | null
  latest: string | null
  barCount: number
  lastError: string | null
}

export interface QuotaView {
  used: number
  remain: number
  total: number
  detail: string
}

export interface RunningJob {
  id: number
  job: string
  /** MANUAL 手工、SCHEDULE 定时、CATCHUP 当天补偿检查补跑 */
  trigger: string
  startedAt: string
  progress: string
}

/** 交易日历覆盖。券商只能给到约 2016-09，更早的是从日 K 线反推的。 */
export interface CalendarView {
  earliest: string | null
  latest: string | null
  days: number
  fromBroker: number
  derived: number
}

export interface CoverageView {
  rows: number
  instruments: number
  earliest: string | null
  latest: string | null
  universeSize: number
  poolSize: number
  holdingSize: number
  /** 基准标的（只采集不选股，如纳指 100 ETF） */
  benchmarkSize: number
  unresolved: number
  universeCovered: number
  deepCovered: number
  withErrors: number
  rehabCovered: number
  calendar: CalendarView
  quota: QuotaView
  runningJob: RunningJob | null
}

export interface JobRun {
  id: number
  job: string
  trigger: string
  startedAt: string
  finishedAt: string | null
  /** SKIPPED = 调度重试到底仍被占用而放弃 */
  status: 'RUNNING' | 'OK' | 'PARTIAL' | 'FAILED' | 'SKIPPED'
  summary: string | null
}

export interface DailyBar {
  tradeDate: string
  open: number
  high: number
  low: number
  close: number
  lastClose: number | null
  volume: number
  turnover: number | null
  turnoverRate: number | null
  changeRate: number | null
  pe: number | null
  blank: boolean
}

export type Adjust = 'none' | 'forward' | 'backward'

export interface TradingDay {
  date: string
  kind: number
  /** FUTU 券商给的（约 2016-09 起）；DERIVED 从日 K 线反推（更早，券商取不到） */
  source: 'FUTU' | 'DERIVED'
}

export const getCoverage = async () => (await http.get<CoverageView>('/api/bars/coverage')).data
export const getCalendar = async (from?: string, to?: string) =>
  (await http.get<TradingDay[]>('/api/bars/calendar', { params: { ...(from ? { from } : {}), ...(to ? { to } : {}) } })).data
export interface GapView {
  symbol: string
  name: string | null
  missing: number
  firstMissing: string
  lastMissing: string
}

/** 对照交易日历深扫缺口；前收连续性检查查不出这类问题。 */
export const getGaps = async (limit = 50) =>
  (await http.get<GapView[]>('/api/bars/gaps', { params: { limit } })).data
export const backfillCalendar = async () => (await http.post<{ jobId: number }>('/api/bars/calendar/backfill')).data

/** 落在交易日历之外的 K 线（券商在美股假日给过脏数据）。 */
export interface PhantomCleanup {
  applied: boolean
  found: number
  deleted: number
  bars: {
    symbol: string
    tradeDate: string
    open: number
    high: number
    low: number
    close: number
    volume: number
    turnover: number | null
  }[]
}

/** apply 默认 false 只试跑列清单；确认无误后传 true 才真删。 */
export const cleanupPhantomBars = async (apply = false) =>
  (await http.post<PhantomCleanup>('/api/bars/cleanup/phantom', null, { params: { apply } })).data
export const getJobs = async (limit = 15) => (await http.get<{ running: RunningJob | Record<string, never>; recent: JobRun[] }>('/api/jobs', { params: { limit } })).data
export const getPool = async () => (await http.get<InstrumentView[]>('/api/pool')).data
export const getUniverse = async (index?: string) => (await http.get<InstrumentView[]>('/api/universe', { params: index ? { index } : {} })).data
export const addToPool = async (symbol: string, role: 'POOL' | 'HOLDING' | 'BENCHMARK') => (await http.post(`/api/pool/${symbol}`, null, { params: { role } })).data
export const removeFromPool = async (symbol: string) => (await http.delete(`/api/pool/${symbol}`)).data
export const syncUniverse = async () => (await http.post<{ jobId: number }>('/api/universe/sync')).data
export const refreshUniverse = async (count: number) => (await http.post<{ jobId: number }>('/api/bars/refresh/universe', null, { params: { count } })).data
export const backfillPending = async () => (await http.post<{ jobId: number }>('/api/bars/backfill')).data
export const refreshRehab = async (all: boolean) => (await http.post<{ jobId: number }>('/api/bars/rehab/refresh', null, { params: { all } })).data
export const runIncrement = async () => (await http.post<{ jobId: number }>('/api/bars/increment')).data
export const cancelJob = async () => (await http.post('/api/jobs/cancel')).data
export const getBars = async (symbol: string, from: string, to: string, adjust: Adjust) =>
  (await http.get<DailyBar[]>(`/api/bars/${symbol}`, { params: { from, to, adjust } })).data
