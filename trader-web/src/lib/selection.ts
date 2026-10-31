import type { Exclusion, Metric, Rating, ScoreView } from '../api/selection'

/** Chinese labels and formatting for quant selection (ARCHITECTURE §25.4). */

export const RATING_LABEL: Record<Rating, string> = {
  STRONG_BUY: '强烈买入', BUY: '买入', HOLD: '持有', SELL: '卖出', STRONG_SELL: '强烈卖出',
}
export const RATINGS: Rating[] = ['STRONG_BUY', 'BUY', 'HOLD', 'SELL', 'STRONG_SELL']
/** Rating colors: reuse the project's green-up / red-down (--tr-up green / --tr-down red); the second tier is semi-transparent, the middle tier gray */
export const RATING_COLOR: Record<Rating, string> = {
  STRONG_BUY: 'var(--tr-up)', BUY: 'var(--tr-up-fill)', HOLD: 'var(--el-border-color)',
  SELL: 'var(--tr-down-fill)', STRONG_SELL: 'var(--tr-down)',
}
export const RATING_TAG: Record<Rating, 'success' | 'info' | 'danger' | 'warning' | 'primary'> = {
  STRONG_BUY: 'success', BUY: 'success', HOLD: 'info', SELL: 'danger', STRONG_SELL: 'danger',
}
/** Tag fill vs outline: the strong tiers are solid, the second tiers outlined */
export const RATING_EFFECT: Record<Rating, 'dark' | 'plain' | 'light'> = {
  STRONG_BUY: 'dark', BUY: 'plain', HOLD: 'plain', SELL: 'plain', STRONG_SELL: 'dark',
}
export const RATING_RANK: Record<Rating, number> = { STRONG_BUY: 0, BUY: 1, HOLD: 2, SELL: 3, STRONG_SELL: 4 }

export const EXCLUSION_LABEL: Record<Exclusion, string> = {
  SECURITY_TYPE: '证券类型（ADR / REIT 等）',
  NO_SECTOR: '没有板块',
  SECTOR_TOO_SMALL: '板块不足 8 只',
  PRICE_BELOW_MIN: '股价低于 10 美元',
  NO_PRICE: '没有价格',
  INSUFFICIENT_HISTORY: '季报不足 8 季',
}

export const FACTORS = [
  { key: 'valueScore', label: '估值' },
  { key: 'growthScore', label: '成长' },
  { key: 'profitScore', label: '盈利' },
  { key: 'momentumScore', label: '动能' },
] as const

/** Metrics: name, owning factor, explanation. Raw values are all ratios and are shown as percentages. */
export const METRICS: { key: Metric; label: string; factor: string; hint: string }[] = [
  { key: 'EARNINGS_YIELD', label: '盈利收益率', factor: '估值', hint: '1 / 市盈率(TTM)，亏损为负' },
  { key: 'BOOK_YIELD', label: '账面收益率', factor: '估值', hint: '1 / 市净率' },
  { key: 'SALES_YIELD', label: '销售收益率', factor: '估值', hint: '1 / 市销率' },
  { key: 'REVENUE_GROWTH', label: '营收同比', factor: '成长', hint: '近 4 季 ÷ 前 4 季 − 1' },
  { key: 'EPS_GROWTH', label: 'EPS 同比', factor: '成长', hint: '稀释 EPS，近 4 季对前 4 季' },
  { key: 'GROSS_MARGIN', label: '毛利率', factor: '盈利', hint: 'TTM；银行保险没有' },
  { key: 'ROE', label: 'ROE', factor: '盈利', hint: 'TTM 归母净利 ÷ 期初期末权益均值' },
  { key: 'FCF_MARGIN', label: '自由现金流利润率', factor: '盈利', hint: 'TTM；银行保险不计' },
  { key: 'MOMENTUM_3M', label: '3 个月涨幅', factor: '动能', hint: '63 个交易日，拆股调整价' },
  { key: 'MOMENTUM_6M', label: '6 个月涨幅', factor: '动能', hint: '126 个交易日' },
  { key: 'MOMENTUM_12M', label: '12 个月涨幅', factor: '动能', hint: '252 个交易日' },
]

/** The metrics column is JSON text {metric: [raw value, percentile within sector]}; a parse failure yields an empty object. */
export function parseMetrics(row: Pick<ScoreView, 'metrics'>): Partial<Record<Metric, [number | null, number | null]>> {
  try {
    return JSON.parse(row.metrics) as Partial<Record<Metric, [number | null, number | null]>>
  } catch {
    return {}
  }
}

/** A 0~100 score, rounded to an integer */
export const score = (v: number | null | undefined): string => (v === null || v === undefined ? '—' : v.toFixed(0))
