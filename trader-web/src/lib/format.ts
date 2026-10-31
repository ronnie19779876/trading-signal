/**
 * Site-wide formatting. Four pages used to have their own copies: three versions of num (with different
 * decimal-place rules), two of fmt and three of errMsg, so the same number looked different on different pages.
 * This is the only copy.
 *
 * Dates are always US/Eastern: batch jobs, trading days and AI usage are all US/Eastern calendar days, whereas
 * toISOString() is UTC, which in Beijing rolls over to "tomorrow" from the afternoon on (the signals page's
 * "calls today N" showed 0 because of this).
 */

const DASH = '—'
const ET = 'America/New_York'

/** Prices: fixed number of decimals (1234.5 -> "1,234.50"). */
export function num(v: number | null | undefined, digits = 2): string {
  return v === null || v === undefined
    ? DASH
    : v.toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits })
}

/** Metrics: up to `digits` decimals, integers are not zero-padded (96221000000 -> "96,221,000,000"). */
export function numMax(v: number | null | undefined, digits = 2): string {
  return v === null || v === undefined ? DASH : v.toLocaleString(undefined, { maximumFractionDigits: digits })
}

/** Money: same rules as num; the separate name only makes the meaning visible when reading the code. */
export const money = (v: number | null | undefined): string => num(v, 2)

/**
 * Money with an explicit sign (+1,234.00 / -56.00). The sign is decided on the unrounded value, so a value such
 * as -0.004 renders as "-0.00".
 */
export function signed(v: number | null | undefined): string {
  if (v === null || v === undefined) return DASH
  return (v > 0 ? '+' : '') + money(v)
}

/** Ratio -> percentage (0.0435 -> "4.35%"). */
export function pct(ratio: number | null | undefined, digits = 2): string {
  return ratio === null || ratio === undefined ? DASH : (ratio * 100).toFixed(digits) + '%'
}

/** R multiple (+1.63R / -0.42R). */
export function signedR(v: number | null | undefined): string {
  return v === null || v === undefined ? DASH : (v > 0 ? '+' : '') + v.toFixed(2) + 'R'
}

/** Large numbers such as market cap are shown in Chinese units of 1e8 / 1e12 so they are readable. */
export function big(v: number | null | undefined): string {
  if (v === null || v === undefined) return DASH
  const yi = v / 1e8
  return Math.abs(yi) >= 10000 ? `${(yi / 10000).toFixed(2)} 万亿` : `${yi.toFixed(2)} 亿`
}

/** CSS class for the direction of a change (colors live in tokens.css: US convention, green up, red down). */
export function trend(v: number | null | undefined): string {
  if (v === null || v === undefined || v === 0) return ''
  return v > 0 ? 'up' : 'down'
}

/** Color negative values (a negative P/E is genuine data for a loss-making company, not an anomaly). */
export function negative(v: number | null | undefined): string {
  return v !== null && v !== undefined && v < 0 ? 'down' : ''
}

/** US/Eastern date and time. */
export function fmtEt(iso: string | null | undefined): string {
  if (!iso) return DASH
  return new Date(iso).toLocaleString('zh-CN', { timeZone: ET, hour12: false }) + ' ET'
}

/** US/Eastern hh:mm:ss (for places that only care about "how long ago"). */
export function timeEt(iso: string | null | undefined): string {
  if (!iso) return DASH
  return new Date(iso).toLocaleTimeString('zh-CN', { timeZone: ET, hour12: false })
}

/** US/Eastern calendar day as YYYY-MM-DD. The en-CA date format happens to be exactly ISO. */
export function isoEt(d: Date = new Date()): string {
  return d.toLocaleDateString('en-CA', { timeZone: ET })
}

/** Today in US/Eastern. */
export const todayEt = (): string => isoEt()

/**
 * The US/Eastern date n days ago. Subtracts n x 24 h, so within an hour of midnight next to a DST change the result
 * can be off by one day; callers only use it for coarse range starts.
 */
export const daysAgoEt = (n: number): string => isoEt(new Date(Date.now() - n * 86_400_000))

/** API errors carry {code, message}, which is far more useful than axios's "Request failed with status code 409". */
export function errMsg(e: unknown): string {
  const r = (e as { response?: { status?: number; data?: { message?: string } } }).response
  return r?.data?.message ? `${r.status}：${r.data.message}` : String(e)
}
