import { http } from './http'
import type { AuditReport } from './account'

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
  /** Actual number of daily bars (taken from daily_bar since 3.0.7; before that it was the row count of the last write in the sync state, which was always 6 for every instrument) */
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
  /** MANUAL = by hand, SCHEDULE = scheduled, CATCHUP = re-run by the same-day catch-up check */
  trigger: string
  startedAt: string
  progress: string
}

/** Trading-calendar coverage. The broker only goes back to about 2016-09; earlier days are derived from daily bars. */
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
  /** Benchmark instruments (collected but never selected, e.g. a Nasdaq-100 ETF) */
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
  /** SKIPPED = the scheduler retried until it gave up because another job still held the slot */
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
  /** FUTU = provided by the broker (from about 2016-09); DERIVED = inferred from daily bars (earlier, unavailable from the broker) */
  source: 'FUTU' | 'DERIVED'
}

export const getCoverage = async () => (await http.get<CoverageView>('/api/bars/coverage')).data
/** Daily-bar audit (section one of the after-close daily check); defaults to the latest trading day that should have a settled bar */
export const getBarAudit = async (date?: string) =>
  (await http.get<AuditReport>('/api/bars/audit', { params: date ? { date } : {}, timeout: 120_000 })).data
export const getCalendar = async (from?: string, to?: string) =>
  (await http.get<TradingDay[]>('/api/bars/calendar', { params: { ...(from ? { from } : {}), ...(to ? { to } : {}) } })).data
export interface GapView {
  symbol: string
  name: string | null
  missing: number
  firstMissing: string
  lastMissing: string
}

/** Deep scan for gaps against the trading calendar; the prior-close continuity check cannot detect these. */
export const getGaps = async (limit = 50) =>
  (await http.get<GapView[]>('/api/bars/gaps', { params: { limit } })).data
export const backfillCalendar = async () => (await http.post<{ jobId: number }>('/api/bars/calendar/backfill')).data

/**
 * Bars that fall outside the trading calendar (the broker has returned junk bars on US market holidays).
 * found is the total; listed (= bars.length) is how many are listed this time, which is also the most this call
 * will delete; deleted can only be 0 or listed; remaining is what is left afterwards -- when found > listed another
 * round is needed.
 */
export interface PhantomCleanup {
  applied: boolean
  found: number
  listed: number
  deleted: number
  remaining: number
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

/** apply defaults to false and only does a dry run that lists the bars; pass true after checking to really delete -- only the listed ones are deleted, the rest wait for the next round. */
export const cleanupPhantomBars = async (apply = false) =>
  (await http.post<PhantomCleanup>('/api/bars/cleanup/phantom', null, { params: { apply } })).data
export const getJobs = async (limit = 15) => (await http.get<{ running: RunningJob | Record<string, never>; recent: JobRun[] }>('/api/jobs', { params: { limit } })).data
export const getPool = async () => (await http.get<InstrumentView[]>('/api/pool')).data
export const getUniverse = async (index?: string) => (await http.get<InstrumentView[]>('/api/universe', { params: index ? { index } : {} })).data
/** HOLDING is maintained automatically from IBKR positions and a manual add returns 409, so it is not accepted here. */
export const addToPool = async (symbol: string, role: 'POOL' | 'BENCHMARK') =>
  (await http.post(`/api/pool/${encodeURIComponent(symbol)}`, null, { params: { role } })).data
export const removeFromPool = async (symbol: string) => (await http.delete(`/api/pool/${encodeURIComponent(symbol)}`)).data
/**
 * force bypasses the "removals per sync" guard (see the backend UniverseSyncService.apply).
 * Deliberately not exposed as a page button: what the guard stops is "the source suddenly returns a few hundred
 * fewer names", and a human has to look at the source page before deciding; a one-click override would make the
 * guard pointless. To override, use curl / Postman with an explicit force=true.
 */
export const syncUniverse = async (force = false) =>
  (await http.post<{ jobId: number }>('/api/universe/sync', null, { params: force ? { force: true } : {} })).data
export const refreshUniverse = async (count: number) => (await http.post<{ jobId: number }>('/api/bars/refresh/universe', null, { params: { count } })).data
export const backfillPending = async () => (await http.post<{ jobId: number }>('/api/bars/backfill')).data
export const refreshRehab = async (all: boolean) => (await http.post<{ jobId: number }>('/api/bars/rehab/refresh', null, { params: { all } })).data
export const runIncrement = async () => (await http.post<{ jobId: number }>('/api/bars/increment')).data
export const cancelJob = async () => (await http.post('/api/jobs/cancel')).data
export const getBars = async (symbol: string, from: string, to: string, adjust: Adjust) =>
  (await http.get<DailyBar[]>(`/api/bars/${encodeURIComponent(symbol)}`, { params: { from, to, adjust } })).data
