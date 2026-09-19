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

/** 实时账户（3.0.2，GET /api/account/live）：盈透常驻订阅，只读内存；全是盈透原值，不做折算；各部分自带更新时间。 */
export type LiveStatus = 'LIVE' | 'WARMING' | 'DISCONNECTED' | 'UNAVAILABLE'

export interface LiveMoney {
  netLiquidation: number | null
  totalCash: number | null
  availableFunds: number | null
  buyingPower: number | null
  excessLiquidity: number | null
  grossPositionValue: number | null
  stockMarketValue: number | null
  accruedDividend: number | null
  /** 盈透账户汇总约 3 分钟才推一次 */
  updatedAt: string
}

/** 盈透账户盈亏原值（reqPnL）；还没收到有效推送时整个为 null */
export interface LivePnl {
  daily: number | null
  unrealized: number | null
  realized: number | null
  updatedAt: string
}

export interface LivePosition {
  symbol: string
  conId: string
  securityType: string | null
  currency: string | null
  quantity: number
  averageCost: number | null
  /** 成本（App 的 Cost Basis）= 成本价 × 数量 */
  costBasis: number | null
  /** 盈透行情最新价（与 App「最新价」同源）；行情还没到时为 null */
  last: number | null
  /** 盈透行情前收（与 App「PRIOR CLOSE」同值） */
  priorClose: number | null
  /** 涨跌 = 最新价 − 前收；涨跌 % 两位小数（与 App 同口径） */
  change: number | null
  changePct: number | null
  lastAt: string | null
  /** true = 盈透降级成了延迟行情 */
  lastDelayed: boolean
  /** 以下三项来自盈透逐只盈亏 */
  marketValue: number | null
  dailyPnl: number | null
  unrealizedPnl: number | null
  /** 占组合（App 的 % of Portfolio）= 市值 ÷ 净值 × 100 */
  portfolioPct: number | null
  cashEquivalent: boolean
  updatedAt: string | null
}

export interface LiveView {
  status: LiveStatus
  detail: string | null
  accountMask: string | null
  currency: string | null
  startedAt: string | null
  money: LiveMoney | null
  pnl: LivePnl | null
  positions: LivePosition[]
  positionsUpdatedAt: string | null
  lastError: string | null
}

export const accountApi = {
  /** 第一次读会发起订阅（WARMING），5 分钟没人读自动退订。 */
  live: async () => (await http.get<LiveView>('/api/account/live')).data,
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
