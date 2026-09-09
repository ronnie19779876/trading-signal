import { http } from './http'

/**
 * 估值快照。个股与 ETF 共用一张表，两套口径：
 * ETF 没有 pe/pb/marketCap，个股没有 navPerShare/premium，缺的都是 null。
 * 亏损股的 pe/pb 为负是真实数据，不要当成异常过滤掉。
 */
export interface ValuationSnapshot {
  instrument: { market: string; symbol: string }
  asOf: string
  suspended: boolean
  marketCap: number | null
  floatMarketCap: number | null
  issuedShares: number | null
  outstandingShares: number | null
  pe: number | null
  peTtm: number | null
  pb: number | null
  eps: number | null
  netAssetPerShare: number | null
  netAsset: number | null
  netProfit: number | null
  dividendTtm: number | null
  dividendYieldTtm: number | null
  turnoverRate: number | null
  navPerShare: number | null
  premium: number | null
}

export interface FinancialItem {
  fieldId: number
  name: string | null
  value: number | null
  yoy: number | null
  qoq: number | null
}

/** 一期财报。年报与四季报的 periodEnd 可能相同，用 periodText 区分；fiscalYear 可能领先自然年。 */
export interface FinancialReport {
  instrument: { market: string; symbol: string }
  statement: 'INCOME' | 'BALANCE_SHEET' | 'CASH_FLOW' | 'MAIN_INDEX'
  periodEnd: string
  fiscalYear: number
  periodText: string
  currency: string | null
  accountingStandards: string | null
  auditorReport: string | null
  items: FinancialItem[]
}

export interface FundamentalsOverview {
  symbol: string
  name: string | null
  valuation: ValuationSnapshot | null
  mainIndex: FinancialReport[]
  profile: Record<string, string>
}

export interface FundamentalsCoverage {
  latestDate: string | null
  targets: number
  withValuationOnDate: number
  reports: number
  poolWithReports: number
  poolSize: number
}

export type StatementKind = 'income' | 'balance_sheet' | 'cash_flow' | 'main_index'

export const fundamentalsApi = {
  overview: async (symbol: string) =>
    (await http.get<FundamentalsOverview>(`/api/fundamentals/${encodeURIComponent(symbol)}`)).data,
  valuation: async (symbol: string, from?: string, to?: string) =>
    (await http.get<ValuationSnapshot[]>(`/api/fundamentals/${encodeURIComponent(symbol)}/valuation`, {
      params: { ...(from ? { from } : {}), ...(to ? { to } : {}) },
    })).data,
  reports: async (symbol: string, statement: StatementKind = 'main_index', limit = 8) =>
    (await http.get<FinancialReport[]>(`/api/fundamentals/${encodeURIComponent(symbol)}/reports`, {
      params: { statement, limit },
    })).data,
  coverage: async () => (await http.get<FundamentalsCoverage>('/api/fundamentals/coverage')).data,
  refreshValuation: async () => (await http.post<{ jobId: number }>('/api/fundamentals/valuation/refresh')).data,
  /** all=true 做全量成分股，约 41 分钟且不取公司简介；默认只做池与持仓。 */
  refreshFinancials: async (all = false) =>
    (await http.post<{ jobId: number }>('/api/fundamentals/financials/refresh', null, { params: { all } })).data,
}
