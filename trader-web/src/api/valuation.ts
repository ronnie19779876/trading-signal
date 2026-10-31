import { http } from './http'

/** Scenario. All three are required: a single-point valuation of an option-like business is meaningless. */
export type Scenario = 'BEAR' | 'BASE' | 'BULL'

export const SCENARIOS: Scenario[] = ['BEAR', 'BASE', 'BULL']
export const SCENARIO_LABEL: Record<Scenario, string> = { BEAR: '熊', BASE: '基准', BULL: '牛' }

/** Assumptions for one business line in one scenario. netMargin is a **decimal** (send 0.07 for 7%); sending a percentage gets a 400 from the backend. */
export interface SegmentCase {
  volume: number | null
  price: number | null
  netMargin: number | null
  pe: number | null
}

export interface Segment {
  name: string
  /** Scope note, required: guards against double counting (the same cars counted both as car sales and as ride-hailing) */
  scopeNote: string
  cases: Partial<Record<Scenario, SegmentCase>>
}

export interface ModelRequest {
  name?: string | null
  asOf: string
  targetYear: number
  /** Required rate of return, decimal */
  discountRate: number
  targetShares: number
  targetNetCash: number
  note?: string | null
  segments: Segment[]
}

export interface ScenarioValue {
  segmentValues: Record<string, number>
  segmentTotal: number
  equityValue: number
  targetPrice: number
  /** Share price discounted back to the base date -- this is the only figure comparable with the current price */
  presentValue: number
  upsidePct: number | null
}

export interface SotpResult {
  horizonYears: number
  discountFactor: number
  scenarios: Record<Scenario, ScenarioValue>
  /** One-factor sensitivity: move one business line only, keep the rest at base; sorted by swing, largest first */
  sensitivities: { segment: string; lowPresentValue: number; highPresentValue: number; swing: number }[]
  marginCheck: { impliedNetMargin: number; actualNetMargin: number | null; deviationPp: number | null; ok: boolean }
  /** null as a whole when there is no P/E baseline: no hard-coded multiple */
  reverse: {
    requiredTargetPrice: number
    requiredMarketCap: number
    requiredNetIncome: number
    requiredNetIncomeCagr: number | null
  } | null
}

/** Company-level base figures filled in automatically; all read-only. */
export interface SotpBasis {
  symbol: string
  name: string
  priceDate: string | null
  price: number | null
  /** All three definitions are given and the system does not choose for the user: measured, the three numbers differ for the same stock */
  shares: { outstanding: number | null; byMarketCap: number | null; byDilutedEps: number | null }
  netCash: {
    cashAndShortTerm: number | null
    borrowings: number | null
    /** Finance leases; not counted as interest-bearing debt by default */
    leases: number | null
    netCash: number | null
    periodText: string | null
  }
  ttmRevenue: number | null
  ttmNetIncome: number | null
  netMarginTtm: number | null
  /** Median of the positive daily-bar P/E over the last 5 years; 0 means no data, not a real value, and is excluded */
  peMedian: number | null
  applicability: { verdict: 'APPLICABLE' | 'CAUTION' | 'NOT_APPLICABLE'; reasons: string[] }
}

/**
 * A saved model: anything persisted always has a name, so name is not nullable here.
 * asOf is the valuation base date stored back then (echoed as-is); valuedAt is the date actually discounted to this
 * time (= the later of the current price's trading day and asOf). When they differ the model was saved a while ago
 * and the present value has been recomputed for the current price's date, so that the "present value" and the
 * "current price" it is compared with belong to the same day.
 */
export interface SavedModel {
  id: number
  symbol: string
  name: string
  asOf: string
  valuedAt: string
  targetYear: number
  discountRate: number
  targetShares: number
  targetNetCash: number
  note: string | null
  segments: Segment[]
  basis: SotpBasis
  result: SotpResult
}

export const valuationApi = {
  inputs: async (symbol: string) =>
    (await http.get<SotpBasis>(`/api/valuation/sotp/${encodeURIComponent(symbol)}/inputs`)).data,
  models: async (symbol: string) =>
    (await http.get<SavedModel[]>(`/api/valuation/sotp/${encodeURIComponent(symbol)}`)).data,
  /** Dry-run calculation, not persisted */
  calc: async (symbol: string, body: ModelRequest) =>
    (await http.post<SotpResult>(`/api/valuation/sotp/calc/${encodeURIComponent(symbol)}`, body)).data,
  /** Save, overwriting any model with the same name */
  save: async (symbol: string, body: ModelRequest) =>
    (await http.post<SavedModel>(`/api/valuation/sotp/${encodeURIComponent(symbol)}`, body)).data,
  remove: async (id: number) => (await http.delete(`/api/valuation/sotp/${id}`)).data,
}
