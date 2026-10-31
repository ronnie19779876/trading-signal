import { http } from './http'
import type { AuditReport } from './account'

/**
 * Valuation snapshot. Stocks and ETFs share one table with two sets of fields: ETFs have no pe/pb/marketCap,
 * stocks have no navPerShare/premium, and the missing ones are null.
 * A negative pe/pb for a loss-making stock is genuine data; do not filter it out as an anomaly.
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

/** One reporting period. The annual report and Q4 can share the same periodEnd, so tell them apart by periodText; fiscalYear can run ahead of the calendar year. */
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

/** Valuations of every instrument on one day (the market-wide screen); date null means there are no valuations in the database yet */
export interface DayValuations {
  date: string | null
  rows: ValuationSnapshot[]
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
  /** Market-wide valuations: without date, the latest day that has valuations */
  valuationsOn: async (date?: string) =>
    (await http.get<DayValuations>('/api/fundamentals/valuations', { params: date ? { date } : {} })).data,
  coverage: async () => (await http.get<FundamentalsCoverage>('/api/fundamentals/coverage')).data,
  /** Fundamentals audit (section two of the after-close daily check) */
  audit: async (date?: string) =>
    (await http.get<AuditReport>('/api/fundamentals/audit', { params: date ? { date } : {}, timeout: 60_000 })).data,
  refreshValuation: async () => (await http.post<{ jobId: number }>('/api/fundamentals/valuation/refresh')).data,
  /** all=true covers all index constituents, about 41 minutes and without company profiles; by default only the pool and holdings. */
  refreshFinancials: async (all = false) =>
    (await http.post<{ jobId: number }>('/api/fundamentals/financials/refresh', null, { params: { all } })).data,
}
