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
  role: 'POOL' | 'HOLDING' | null
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
  trigger: string
  startedAt: string
  progress: string
}

export interface CoverageView {
  rows: number
  instruments: number
  earliest: string | null
  latest: string | null
  universeSize: number
  poolSize: number
  holdingSize: number
  unresolved: number
  universeCovered: number
  deepCovered: number
  withErrors: number
  rehabCovered: number
  quota: QuotaView
  runningJob: RunningJob | null
}

export interface JobRun {
  id: number
  job: string
  trigger: string
  startedAt: string
  finishedAt: string | null
  status: 'RUNNING' | 'OK' | 'PARTIAL' | 'FAILED'
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

export const getCoverage = async () => (await http.get<CoverageView>('/api/bars/coverage')).data
export const getJobs = async (limit = 15) => (await http.get<{ running: RunningJob | Record<string, never>; recent: JobRun[] }>('/api/jobs', { params: { limit } })).data
export const getPool = async () => (await http.get<InstrumentView[]>('/api/pool')).data
export const getUniverse = async (index?: string) => (await http.get<InstrumentView[]>('/api/universe', { params: index ? { index } : {} })).data
export const addToPool = async (symbol: string, role: 'POOL' | 'HOLDING') => (await http.post(`/api/pool/${symbol}`, null, { params: { role } })).data
export const removeFromPool = async (symbol: string) => (await http.delete(`/api/pool/${symbol}`)).data
export const syncUniverse = async () => (await http.post<{ jobId: number }>('/api/universe/sync')).data
export const refreshUniverse = async (count: number) => (await http.post<{ jobId: number }>('/api/bars/refresh/universe', null, { params: { count } })).data
export const backfillPending = async () => (await http.post<{ jobId: number }>('/api/bars/backfill')).data
export const refreshRehab = async (all: boolean) => (await http.post<{ jobId: number }>('/api/bars/rehab/refresh', null, { params: { all } })).data
export const runIncrement = async () => (await http.post<{ jobId: number }>('/api/bars/increment')).data
export const cancelJob = async () => (await http.post('/api/jobs/cancel')).data
export const getBars = async (symbol: string, from: string, to: string, adjust: Adjust) =>
  (await http.get<DailyBar[]>(`/api/bars/${symbol}`, { params: { from, to, adjust } })).data
