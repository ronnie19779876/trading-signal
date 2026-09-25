import { http } from './http'

/** 情景。三个缺一不可：期权型业务单点估值没有意义。 */
export type Scenario = 'BEAR' | 'BASE' | 'BULL'

export const SCENARIOS: Scenario[] = ['BEAR', 'BASE', 'BULL']
export const SCENARIO_LABEL: Record<Scenario, string> = { BEAR: '熊', BASE: '基准', BULL: '牛' }

/** 一条业务线在某个情景下的假设。netMargin 是**小数**（7% 传 0.07），写成百分数后端直接 400。 */
export interface SegmentCase {
  volume: number | null
  price: number | null
  netMargin: number | null
  pe: number | null
}

export interface Segment {
  name: string
  /** 口径备注，必填：用来挡住重复计算（同一批车既算卖车又算打车） */
  scopeNote: string
  cases: Partial<Record<Scenario, SegmentCase>>
}

export interface ModelRequest {
  name?: string | null
  asOf: string
  targetYear: number
  /** 要求回报率，小数 */
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
  /** 折回基准日的股价——和现价比只能比这个 */
  presentValue: number
  upsidePct: number | null
}

export interface SotpResult {
  horizonYears: number
  discountFactor: number
  scenarios: Record<Scenario, ScenarioValue>
  /** 单因子敏感度：只动一条业务线、其余保持基准，按摆幅倒序 */
  sensitivities: { segment: string; lowPresentValue: number; highPresentValue: number; swing: number }[]
  marginCheck: { impliedNetMargin: number; actualNetMargin: number | null; deviationPp: number | null; ok: boolean }
  /** 缺本益比基准时整个为 null：不硬编倍数 */
  reverse: {
    requiredTargetPrice: number
    requiredMarketCap: number
    requiredNetIncome: number
    requiredNetIncomeCagr: number | null
  } | null
}

/** 自动带入的公司级底座，全部只读。 */
export interface SotpBasis {
  symbol: string
  name: string
  priceDate: string | null
  price: number | null
  /** 三种口径都给，系统不替使用者选：实测同一只票三个数互不相同 */
  shares: { outstanding: number | null; byMarketCap: number | null; byDilutedEps: number | null }
  netCash: {
    cashAndShortTerm: number | null
    borrowings: number | null
    /** 融资租赁，默认不计入有息负债 */
    leases: number | null
    netCash: number | null
    periodText: string | null
  }
  ttmRevenue: number | null
  ttmNetIncome: number | null
  netMarginTtm: number | null
  /** 近 5 年日 K 市盈率正值中位数；0 是无数据不是真值，已剔除 */
  peMedian: number | null
  applicability: { verdict: 'APPLICABLE' | 'CAUTION' | 'NOT_APPLICABLE'; reasons: string[] }
}

/**
 * 已存的方案：落库的一定有名称，所以这里的 name 不是可空的。
 * asOf 是当初存下来的估值基准日（原样回显）；valuedAt 是本次实际折算到的日期
 * （= 现价所属交易日与 asOf 中较晚的那个）。两者不同说明方案存了一段时间，
 * 现值已经按现价所属日重算，好让「现值」与它比较的「现价」属于同一天。
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
  /** 试算，不落库 */
  calc: async (symbol: string, body: ModelRequest) =>
    (await http.post<SotpResult>(`/api/valuation/sotp/calc/${encodeURIComponent(symbol)}`, body)).data,
  /** 按 name 覆盖保存 */
  save: async (symbol: string, body: ModelRequest) =>
    (await http.post<SavedModel>(`/api/valuation/sotp/${encodeURIComponent(symbol)}`, body)).data,
  remove: async (id: number) => (await http.delete(`/api/valuation/sotp/${id}`)).data,
}
