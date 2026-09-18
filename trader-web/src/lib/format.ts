/**
 * 全站格式化。此前四个页面各写一份：三套 num（小数位规则不一样）、两套 fmt、三套 errMsg，
 * 同一个数在不同页面显示得不一样。这里是唯一一份。
 *
 * 日期一律按美东：跑批、交易日、AI 用量都是美东自然日，而 toISOString() 取的是 UTC，
 * 北京时间下午之后就跨到了"明天"（信号页的"今天调用 N"因此会显示 0）。
 */

const DASH = '—'
const ET = 'America/New_York'

/** 价格类：固定小数位（1234.5 → "1,234.50"）。 */
export function num(v: number | null | undefined, digits = 2): string {
  return v === null || v === undefined
    ? DASH
    : v.toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits })
}

/** 指标类：最多几位，整数不补零（96221000000 → "96,221,000,000"）。 */
export function numMax(v: number | null | undefined, digits = 2): string {
  return v === null || v === undefined ? DASH : v.toLocaleString(undefined, { maximumFractionDigits: digits })
}

/** 金额：与 num 同口径，另起名字是为了读代码时看得出语义。 */
export const money = (v: number | null | undefined): string => num(v, 2)

/** 带正号的金额（+1,234.00 / -56.00）。 */
export function signed(v: number | null | undefined): string {
  if (v === null || v === undefined) return DASH
  return (v > 0 ? '+' : '') + money(v)
}

/** 比例 → 百分数（0.0435 → "4.35%"）。 */
export function pct(ratio: number | null | undefined, digits = 2): string {
  return ratio === null || ratio === undefined ? DASH : (ratio * 100).toFixed(digits) + '%'
}

/** R 倍数（+1.63R / -0.42R）。 */
export function signedR(v: number | null | undefined): string {
  return v === null || v === undefined ? DASH : (v > 0 ? '+' : '') + v.toFixed(2) + 'R'
}

/** 市值这类大数按亿显示，读起来才有概念。 */
export function big(v: number | null | undefined): string {
  if (v === null || v === undefined) return DASH
  const yi = v / 1e8
  return Math.abs(yi) >= 10000 ? `${(yi / 10000).toFixed(2)} 万亿` : `${yi.toFixed(2)} 亿`
}

/** 中国习惯：红涨绿跌。返回 tokens.css 里的全局类名。 */
export function trend(v: number | null | undefined): string {
  if (v === null || v === undefined || v === 0) return ''
  return v > 0 ? 'up' : 'down'
}

/** 负值标色（市盈率为负是亏损的真实数据，不是异常）。 */
export function negative(v: number | null | undefined): string {
  return v !== null && v !== undefined && v < 0 ? 'down' : ''
}

/** 美东日期时间。 */
export function fmtEt(iso: string | null | undefined): string {
  if (!iso) return DASH
  return new Date(iso).toLocaleString('zh-CN', { timeZone: ET, hour12: false }) + ' ET'
}

/** 美东时分秒（只关心"多久之前"的场合用它）。 */
export function timeEt(iso: string | null | undefined): string {
  if (!iso) return DASH
  return new Date(iso).toLocaleTimeString('zh-CN', { timeZone: ET, hour12: false })
}

/** 美东自然日 YYYY-MM-DD。en-CA 的日期格式恰好就是 ISO。 */
export function isoEt(d: Date = new Date()): string {
  return d.toLocaleDateString('en-CA', { timeZone: ET })
}

/** 美东今天。 */
export const todayEt = (): string => isoEt()

/** 美东 n 天前。 */
export const daysAgoEt = (n: number): string => isoEt(new Date(Date.now() - n * 86_400_000))

/** 接口错误带着 {code, message}，比 axios 的 "Request failed with status code 409" 有用得多。 */
export function errMsg(e: unknown): string {
  const r = (e as { response?: { status?: number; data?: { message?: string } } }).response
  return r?.data?.message ? `${r.status}：${r.data.message}` : String(e)
}
