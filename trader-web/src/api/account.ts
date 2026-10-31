import { http } from './http'

export type ReconStatus = 'OK' | 'WARN' | 'FAIL'

export interface ReconCheck {
  name: string
  status: ReconStatus
  detail: string
}

/**
 * Account snapshot (one per account per trading day). The account number is only ever exposed masked
 * (accountMask); accountKey is a keyed HMAC used only to tell accounts apart and cannot be reversed into the
 * account number.
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
  /** IBKR's stock market value (the other side of the reconciliation) */
  stockMarketValue: number | null
  grossPositionValue: number | null
  availableFunds: number | null
  buyingPower: number | null
  excessLiquidity: number | null
  unrealizedPnl: number | null
  realizedPnl: number | null
  accruedDividend: number | null
  /** Accrued interest (IBKR $LEDGER-AccruedCash); collected since 3.1.3, null in older snapshots */
  accruedInterest: number | null
  /** This system's own figure: sum of quantity x close (stocks only) */
  positionValue: number | null
  positions: number
  reconStatus: ReconStatus
  recon: ReconCheck[]
  jobRunId: number | null
}

/**
 * One position in a snapshot. symbol is the broker's spelling (share classes contain a space, e.g. BRK B);
 * instrumentId null means the instrument is not in the database.
 * priceSource: BAR = close of that day's daily bar; SNAPSHOT = Futu snapshot price as a fallback; NONE = no price.
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
  /** Cash-management instrument: counted in market value, never added to the pool, excluded from the holdings-set check */
  cashEquivalent: boolean
}

/**
 * Compared with the previous snapshot. netLiquidationChange includes deposits and withdrawals.
 * positionPnl only sums positions whose <b>quantity is the same</b> in both snapshots -- a split/reverse split looks
 * exactly like a trade in a snapshot, and a changed quantity cannot be attributed (on a split day quantity and
 * price both change proportionally), so such positions are skipped and counted in excludedPositions.
 * positionsChanged=true means the set of positions or a quantity changed (a trade, or a split/reverse split).
 */
export interface DailyChange {
  previousDate: string
  netLiquidationChange: number | null
  positionPnl: number | null
  positionsChanged: boolean
  excludedPositions: number
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
    /** Why the plan will not be executed (e.g. IBKR returned no positions while the pool still has HOLDING entries) */
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

/** Live account (3.0.2, GET /api/account/live): a standing IBKR subscription, read from memory only; all values are raw IBKR values with no conversion; each part carries its own update time. */
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
  /** Accrued interest (IBKR $LEDGER-AccruedCash). The fourth term of the identity: cash + stock market value + accrued dividend + accrued interest = NAV */
  accruedInterest: number | null
  /** IBKR pushes the account summary only about every 3 minutes */
  updatedAt: string
}

/** Raw IBKR account P&L (reqPnL); the whole object is null until a valid push has arrived */
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
  /** Cost basis (the app's Cost Basis) = average cost x quantity */
  costBasis: number | null
  /** Latest price from IBKR market data (same source as the app's last price); null until market data arrives */
  last: number | null
  /** Prior close from IBKR market data (same value as the app's PRIOR CLOSE) */
  priorClose: number | null
  /** Change = last - prior close; change % with two decimals (same definition as the app) */
  change: number | null
  changePct: number | null
  lastAt: string | null
  /** true = IBKR fell back to delayed market data */
  lastDelayed: boolean
  /** The next three fields come from IBKR's per-position P&L */
  marketValue: number | null
  dailyPnl: number | null
  unrealizedPnl: number | null
  /** Share of portfolio (the app's % of Portfolio) = market value / NAV x 100 */
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

// ---------------------------------------------------------------- holdings risk panel (3.5.0, ARCHITECTURE §29)
// Only checks the rules the user defined and reports numbers; it gives no buy/sell advice. All ratios are
// percentage numbers (-9.63 means -9.63%).

export type TrendState = 'UP_STRONG' | 'UP_WEAK' | 'ABOVE_FLAT' | 'BELOW' | 'BROKEN' | 'UNAVAILABLE'
export type ReviewTrigger = 'BREAK_CONFIRMED' | 'DEATH_CROSS' | 'CHANDELIER_BREACH' | 'EARNINGS_OVER_BUDGET'

export interface HoldingTrend {
  /** Evaluation date = the latest daily bar of this instrument */
  date: string
  close: number
  smaShort: number | null
  smaLong: number | null
  smaLongPrior: number | null
  state: TrendState
  stateLabel: string
  /** = the entry signal's trend gate (close > 200-day SMA and the SMA rising) */
  trendGatePass: boolean
  pctVsSmaShort: number | null
  pctVsSmaLong: number | null
  belowDays: number
  deathCross: boolean
  deathCrossSince: string | null
  chandelier: number | null
  chandelierBreach: boolean
  pctFrom52wHigh: number | null
  /** Relative to the IBKR average cost (holdings only) */
  pnlVsCostPct: number | null
}

export interface UpcomingEarnings {
  period: string | null
  date: string
  timing: 'PRE_MARKET' | 'DURING_MARKET' | 'AFTER_MARKET' | 'UNKNOWN'
  /** How many trading days after the latest daily bar (0 = after the close that day, or timing unknown) */
  tradingDaysAhead: number
  /** The broker has not scheduled it yet; estimated as the last announcement + 91 days */
  estimated: boolean
  inWindow: boolean
}

export interface EarningsSample {
  period: string
  pubDate: string
  timing: string
  reactionDate: string
  /** Open relative to the reference close; when the timing is unknown, whichever day moved more is used */
  gapPct: number | null
  movePct: number
}

export interface EarningsStats {
  samples: number
  meanAbsMovePct: number
  medianAbsMovePct: number
  maxDropPct: number
  maxRisePct: number
  bigMoves: number
  ups: number
  downs: number
  worstGapPct: number | null
}

export interface EarningsScenario {
  historicalDropPct: number
  stressDropPct: number
  historicalLoss: number | null
  stressLoss: number | null
  stressLossPctOfNav: number | null
  /** null when no loss budget is configured */
  overBudget: boolean | null
}

export interface HoldingReviewItem {
  symbol: string
  role: 'HOLDING' | 'POOL'
  quantity: number | null
  averageCost: number | null
  price: number | null
  marketValue: number | null
  weightPct: number | null
  trend: HoldingTrend | null
  earnings: { next: UpcomingEarnings | null; stats: EarningsStats | null; samples: EarningsSample[]; scenario: EarningsScenario | null } | null
  triggers: { trigger: ReviewTrigger; detail: string }[]
  maxWeightPctInBudget: number | null
  problem: string | null
}

export interface HoldingReview {
  snapshotDate: string | null
  netLiquidation: number | null
  params: {
    windowDays: number
    stressMultiple: number
    lossBudgetPct: number | null
    rules: { smaShort: number; smaLong: number; slopeLookback: number; breakConfirmDays: number; deathCross: boolean;
             atrPeriod: number; chandelierPeriod: number; chandelierAtrMultiple: number }
  }
  items: HoldingReviewItem[]
}

export interface AddCheck {
  symbol: string
  shares: number
  price: number
  priceDate: string
  held: boolean
  allPass: boolean
  conclusion: string
  rules: { rule: 'TREND' | 'EARNINGS_WINDOW' | 'LOSS_BUDGET' | 'NO_AVERAGING_DOWN'; status: 'PASS' | 'FAIL' | 'SKIPPED'; detail: string }[]
}

export const accountApi = {
  /** The first read starts the subscription (WARMING); it unsubscribes automatically after 5 minutes without readers. */
  live: async () => (await http.get<LiveView>('/api/account/live')).data,
  latest: async () => (await http.get<SnapshotView>('/api/account/snapshots/latest')).data,
  snapshots: async (from?: string, to?: string) =>
    (await http.get<AccountSnapshot[]>('/api/account/snapshots', {
      params: { ...(from ? { from } : {}), ...(to ? { to } : {}) },
    })).data,
  /** Only allowed inside the snapshot window (trading days 16:15 US/Eastern until 04:00 the next day); 409 outside it. */
  snapshot: async () => (await http.post<{ jobId: number }>('/api/account/snapshot')).data,
  /** apply=false only shows the plan; apply=true actually changes the pool. Positions missing from the database must be resolved via Futu, hence the longer timeout. */
  syncHoldings: async (apply: boolean) =>
    (await http.post<HoldingSyncResult>('/api/account/holdings/sync', null, { params: { apply }, timeout: 60_000 })).data,
  audit: async (date?: string) =>
    (await http.get<AuditReport>('/api/account/audit', { params: date ? { date } : {} })).data,
  /** Holdings risk panel: computes 6 years of daily bars per instrument, hence the longer timeout. Note that a GET timing out is retried by http.ts, so a timed-out computation can be requested up to three times. */
  holdingsReview: async (days?: number) =>
    (await http.get<HoldingReview>('/api/account/holdings-review', { params: days ? { days } : {}, timeout: 60_000 })).data,
  addCheck: async (symbol: string, shares: number) =>
    (await http.get<AddCheck>(`/api/account/add-check/${encodeURIComponent(symbol)}`, { params: { shares }, timeout: 30_000 })).data,
}
