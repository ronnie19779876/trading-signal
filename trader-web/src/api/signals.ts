import { http } from './http'
import type { AuditReport } from './account'

/** 与 docs/API.md「入场信号（第 4 期）」一一对应。价位一律为判定日口径（当天真实可成交价）。 */

export type Gate = 'TREND' | 'LOCATION' | 'TRIGGER' | 'RISK'
export type Verdict = 'PASS' | 'FAIL' | 'UNAVAILABLE'
export type EvaluationStatus =
  | 'EVALUATED'
  | 'SKIPPED_INSUFFICIENT_BARS'
  | 'SKIPPED_STALE_DATA'
  | 'SKIPPED_DATA_GAP'
  | 'SKIPPED_CORPORATE_ACTION'
export type Outcome = 'SIGNAL' | 'NO_SIGNAL' | 'SUPPRESSED_EDGE' | 'SUPPRESSED_COOLDOWN' | 'BLOCKED_BY_AI'
export type Role = 'POOL' | 'HOLDING' | 'UNIVERSE'
export type SignalStatus = 'NEW' | 'ACKNOWLEDGED' | 'DISMISSED' | 'EXPIRED' | 'VETOED'
export type Origin = 'LIVE' | 'BACKFILL'
export type Variant = 'BASE' | 'STOP_2_5'
export type TrackStatus = 'PENDING_ENTRY' | 'OPEN' | 'CLOSED'
export type ExitReason = 'STOP' | 'CHANDELIER' | 'TIME' | 'OPEN'

export interface GateResult {
  gate: Gate
  verdict: Verdict
  /** 代入了数值的判据原文 */
  criteria: string
  values: Record<string, unknown>
}

export interface PriceZone {
  bottom: number
  top: number
  touches: number
  members: string[]
}

export interface ExitPlan {
  initialStop: number
  riskPerShare: number
  plusOneR: number
  chandelierStop: number
  timeStopDays: number
  targetZone: PriceZone | null
  target: number | null
  rewardRisk: number | null
}

export interface SentinelEvaluation {
  version: string
  asOf: string
  status: EvaluationStatus
  statusDetail: string | null
  gates: GateResult[]
  indicators: Record<string, number | string | null>
  zones: PriceZone[]
  hitZone: PriceZone | null
  exitPlan: ExitPlan | null
  bonus: Record<string, unknown>
}

export interface Judgement {
  symbol: string
  evaluation: SentinelEvaluation
  droppedNonTradingDays: string[]
  missingTradingDays: string[]
  fingerprint: string
  thresholds: Record<string, unknown>
}

export interface EvaluationRow {
  instrumentId: number
  symbol: string
  tradeDate: string
  rulesetVersion: string
  status: EvaluationStatus
  statusDetail: string | null
  outcome: Outcome | null
  /** 四门缩写：P 通过 / F 不过 / U 不可判定；不予判定时为 null */
  gates: string | null
  gatesPassed: number
  firstBlockingGate: Gate | null
  role: Role
  close: number | null
  atr14: number | null
  rvol: number | null
  zoneBottom: number | null
  stop: number | null
  stopDistance: number | null
  inputFingerprint: string
  /** 判定明细：只给池与持仓、至少过三门、结果不是 NO_SIGNAL 的存 */
  detail: { evaluation: SentinelEvaluation; droppedNonTradingDays: string[]; missingTradingDays: string[] } | null
  jobRunId: number | null
  evaluatedAt: string
}

export interface EntrySignal {
  id: number
  instrumentId: number
  symbol: string
  tradeDate: string
  rulesetVersion: string
  role: Role
  origin: Origin
  close: number
  atr14: number
  stop: number
  stopLeg: 'ATR' | 'ZONE'
  stopDistance: number
  riskPerShare: number
  plusOneR: number
  chandelierStop: number | null
  target: number | null
  rewardRisk: number | null
  zoneBottom: number | null
  zoneTop: number | null
  zoneTouches: number | null
  bonus: Record<string, unknown> | null
  aiAnalysisId: number | null
  aiStance: string | null
  status: SignalStatus
  expiresOn: string
  note: string | null
  statusChangedAt: string | null
  jobRunId: number | null
  createdAt: string
}

export interface SignalTrack {
  signalId: number
  variant: Variant
  status: TrackStatus
  stop: number
  plusOneR: number
  entryDate: string | null
  entryPrice: number | null
  touchedPlusOneR: boolean
  exitDate: string | null
  exitPrice: number | null
  exitReason: Exclude<ExitReason, 'OPEN'> | null
  rMultiple: number | null
  returnPct: number | null
  mfeR: number | null
  maeR: number | null
  barsHeld: number | null
  updatedThrough: string | null
  updatedAt: string
}

export interface SignalView {
  signal: EntrySignal
  base: SignalTrack | null
}

export interface SignalDetail {
  signal: EntrySignal
  evaluation: EvaluationRow | null
  /** 按当前库里数据重算的输入指纹与存档一致与否；不一致说明 K 线或因子被重拉改过 */
  fingerprintMatches: boolean
  /** 存档没有判定明细时现场重算 */
  recomputed: Judgement | null
  tracks: SignalTrack[]
}

export interface LedgerStats {
  variant: Variant
  origin: Origin
  /** VETO 被否决 / ALLOW 调过模型并放行 / NONE 没有模型结论 */
  ai: 'VETO' | 'ALLOW' | 'NONE'
  total: number
  open: number
  pending: number
  closed: number
  winRate: number | null
  meanR: number | null
  meanReturn: number | null
}

export interface Ledger {
  stats: LedgerStats[]
  pairedCount: number
  pairedMeanReturnDiff: number | null
  entries: { signal: EntrySignal; track: SignalTrack }[]
}

export interface ReplayDay {
  date: string
  status: EvaluationStatus
  gates: string | null
  gatesPassed: number
  firstBlockingGate: Gate | null
  outcome: Outcome | 'PENDING_AI'
  close: number | null
  atr14: number | null
  rvol: number | null
  zoneBottom: number | null
  stop: number | null
  stopDistance: number | null
  detail: string | null
}

export interface PaperTrade {
  signalDate: string
  entryDate: string
  entry: number
  stop: number
  plusOneR: number
  touchedPlusOneR: boolean
  exitDate: string | null
  exit: number | null
  reason: ExitReason
  r: number | null
  mfeR: number
  maeR: number
  barsHeld: number
}

export interface Replay {
  symbol: string
  from: string
  to: string
  statusCounts: Record<string, number>
  outcomeCounts: Record<string, number>
  days: ReplayDay[]
  exitVariant: string | null
  trades: PaperTrade[] | null
}

/** 画图用 K 线：价格尺度折回 asOf 那天，与当天的信号价位对齐。 */
export interface ChartBar {
  tradeDate: string
  open: number
  high: number
  low: number
  close: number
  volume: number
}

const opt = (o: Record<string, string | number | boolean | null | undefined>) =>
  Object.fromEntries(Object.entries(o).filter(([, v]) => v !== null && v !== undefined && v !== ''))

export const signalsApi = {
  audit: async (date?: string) => (await http.get<AuditReport>('/api/signals/audit', { params: opt({ date }), timeout: 30_000 })).data,
  evaluations: async (p: { date?: string; outcome?: string; gate?: string; scope?: 'pool' | 'all' }) =>
    (await http.get<EvaluationRow[]>('/api/signals/evaluations', { params: opt(p) })).data,
  /** 单只评估历史（倒序）；仪表盘用它找最近一个有评估的日子 */
  history: async (symbol: string, p: { from?: string; to?: string } = {}) =>
    (await http.get<EvaluationRow[]>(`/api/signals/evaluations/${encodeURIComponent(symbol)}`, { params: opt(p) })).data,
  evaluate: async (symbol: string, date?: string) =>
    (await http.get<Judgement>(`/api/signals/evaluate/${encodeURIComponent(symbol)}`, { params: opt({ date }) })).data,
  list: async (p: { from?: string; to?: string; status?: string; scope?: 'pool' | 'all'; origin?: string }) =>
    (await http.get<SignalView[]>('/api/signals', { params: opt(p) })).data,
  detail: async (id: number) => (await http.get<SignalDetail>(`/api/signals/${id}`)).data,
  changeStatus: async (id: number, status: 'ACKNOWLEDGED' | 'DISMISSED', note?: string) =>
    (await http.post<EntrySignal>(`/api/signals/${id}/status`, { status, note: note ?? null })).data,
  ledger: async (p: { variant?: string; status?: string }) =>
    (await http.get<Ledger>('/api/signals/ledger', { params: opt(p) })).data,
  replay: async (symbol: string, p: { from?: string; to?: string; stopAtr?: number; half?: boolean }) =>
    (await http.get<Replay>(`/api/signals/replay/${encodeURIComponent(symbol)}`, {
      params: opt({ ...p, trades: true }),
      timeout: 60_000,
    })).data,
  bars: async (symbol: string, p: { asOf?: string; from?: string; to?: string }) =>
    (await http.get<ChartBar[]>(`/api/signals/bars/${encodeURIComponent(symbol)}`, { params: opt(p) })).data,
}
