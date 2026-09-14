import { http } from './http'

export type ReconStatus = 'OK' | 'WARN' | 'FAIL'

export interface ReconCheck {
  name: string
  status: ReconStatus
  detail: string
}

/**
 * 账户快照（一个账户一个交易日一份）。账户号只给脱敏形式 accountMask；
 * accountKey 是带密钥的 HMAC，只用来区分账户，不能反推账户号。
 */
export interface AccountSnapshot {
  id: number
  broker: string
  accountKey: string
  accountMask: string
  asOfDate: string
  takenAt: string
  currency: string | null
  netLiquidation: number | null
  totalCash: number | null
  /** 盈透口径的股票市值（对账的另一边） */
  stockMarketValue: number | null
  grossPositionValue: number | null
  availableFunds: number | null
  buyingPower: number | null
  excessLiquidity: number | null
  unrealizedPnl: number | null
  realizedPnl: number | null
  accruedDividend: number | null
  /** 本系统口径：Σ 数量 × 收盘价（只算股票） */
  positionValue: number | null
  positions: number
  reconStatus: ReconStatus
  recon: ReconCheck[]
  jobRunId: number | null
}

/**
 * 快照里的一条持仓。symbol 是券商写法（类别股带空格，如 BRK B）；instrumentId 为 null 表示库里没有这个标的。
 * priceSource：BAR 当日 K 线收盘；SNAPSHOT 富途快照价兜底；NONE 缺价。
 */
export interface PositionSnapshot {
  brokerRef: string
  symbol: string
  instrumentId: number | null
  securityType: string | null
  currency: string | null
  exchange: string | null
  quantity: number
  averageCost: number | null
  price: number | null
  priceSource: 'BAR' | 'SNAPSHOT' | 'NONE'
  marketValue: number | null
  costBasis: number | null
  unrealizedPnl: number | null
  /** 现金管理工具：计入市值，不进池、不参与持仓集合核对 */
  cashEquivalent: boolean
}

/** 与上一份快照相比。netLiquidationChange 含出入金；positionsChanged=true 表示当天有买卖，positionPnl 只是近似。 */
export interface DailyChange {
  previousDate: string
  netLiquidationChange: number | null
  positionPnl: number | null
  positionsChanged: boolean
}

export interface SnapshotView {
  snapshot: AccountSnapshot
  positions: PositionSnapshot[]
  change: DailyChange | null
}

export type SyncAction = 'ADD' | 'PROMOTE' | 'RETURN_TO_POOL' | 'REMOVE'

export interface HoldingSyncResult {
  applied: boolean
  plan: {
    changes: { action: SyncAction; symbol: string; instrumentId: number | null }[]
    untouched: string[]
    /** 不执行的原因（如盈透返回空持仓而池里还有 HOLDING） */
    blocked: string | null
  }
  errors: string[]
  summary: string
}

export interface AuditCheck {
  name: string
  ok: boolean
  critical: boolean
  detail: string
  count: number
  samples: string[]
}

export interface AuditReport {
  date: string
  ok: boolean
  generatedAt: string
  summary: Record<string, unknown>
  checks: AuditCheck[]
}

export const accountApi = {
  latest: async () => (await http.get<SnapshotView>('/api/account/snapshots/latest')).data,
  snapshots: async (from?: string, to?: string) =>
    (await http.get<AccountSnapshot[]>('/api/account/snapshots', {
      params: { ...(from ? { from } : {}), ...(to ? { to } : {}) },
    })).data,
  /** 只能在快照窗口内拍（交易日美东 16:15 至次日 04:00），窗口外 409。 */
  snapshot: async () => (await http.post<{ jobId: number }>('/api/account/snapshot')).data,
  /** apply=false 只看计划；apply=true 才改池。库里没有的持仓要向富途解析，放宽超时。 */
  syncHoldings: async (apply: boolean) =>
    (await http.post<HoldingSyncResult>('/api/account/holdings/sync', null, { params: { apply }, timeout: 60_000 })).data,
  audit: async (date?: string) =>
    (await http.get<AuditReport>('/api/account/audit', { params: date ? { date } : {} })).data,
}
