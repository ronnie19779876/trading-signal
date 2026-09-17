import type { Gate, Outcome, SignalStatus, EvaluationStatus, Role, TrackStatus, ExitReason } from '../../api/signals'
import type { Stance, Confidence, AiStatus, AiVerdict } from '../../api/ai'

type TagType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

export const GATE_LABEL: Record<Gate, string> = { TREND: '趋势', LOCATION: '定位', TRIGGER: '触发', RISK: '风控' }
export const GATES: Gate[] = ['TREND', 'LOCATION', 'TRIGGER', 'RISK']

export const OUTCOME_LABEL: Record<Outcome | 'PENDING_AI', string> = {
  SIGNAL: '信号', NO_SIGNAL: '未触发', SUPPRESSED_EDGE: '边沿抑制', SUPPRESSED_COOLDOWN: '冷却抑制', BLOCKED_BY_AI: 'AI 否决', PENDING_AI: '待 AI',
}
export const STATUS_LABEL: Record<EvaluationStatus, string> = {
  EVALUATED: '已判定', SKIPPED_INSUFFICIENT_BARS: 'K 线不足', SKIPPED_STALE_DATA: '数据过期', SKIPPED_DATA_GAP: '缺交易日',
  SKIPPED_CORPORATE_ACTION: '公司行动口径',
}
export const ROLE_LABEL: Record<Role, string> = { POOL: '池', HOLDING: '持仓', UNIVERSE: '池外' }
export const SIGNAL_STATUS_LABEL: Record<SignalStatus, string> = {
  NEW: '新', ACKNOWLEDGED: '已看过', DISMISSED: '不做', EXPIRED: '已过期', VETOED: 'AI 否决',
}
export const SIGNAL_STATUS_TYPE: Record<SignalStatus, TagType> = {
  NEW: 'primary', ACKNOWLEDGED: 'success', DISMISSED: 'info', EXPIRED: 'info', VETOED: 'danger',
}
export const TRACK_LABEL: Record<TrackStatus, string> = { PENDING_ENTRY: '待入场', OPEN: '持有中', CLOSED: '已平仓' }
export const EXIT_LABEL: Record<ExitReason, string> = { STOP: '止损', CHANDELIER: '吊灯止损', TIME: '时间止损', OPEN: '未平仓' }
export const STANCE_LABEL: Record<Stance, string> = { BULLISH: '看多', NEUTRAL: '中性', BEARISH: '看空', AVOID: '回避' }
export const STANCE_TYPE: Record<Stance, TagType> = { BULLISH: 'danger', NEUTRAL: 'info', BEARISH: 'success', AVOID: 'warning' }
export const CONFIDENCE_LABEL: Record<Confidence, string> = { HIGH: '把握高', MEDIUM: '把握中', LOW: '把握低' }
export const AI_STATUS_LABEL: Record<AiStatus, string> = {
  OK: '成功', REFUSED: '拒答', TRUNCATED: '截断', INVALID: '结构非法', FAILED: '调用失败', SKIPPED_BUDGET: '预算跳过', FAILED_DATA: '输入构建失败',
}
export const VERDICT_LABEL: Record<AiVerdict, string> = { VETO: '否决', ALLOW: '放行', ABSENT: '没有结论' }

export function num(v: number | null | undefined, digits = 2): string {
  return v === null || v === undefined ? '—' : v.toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits })
}

export function pct(ratio: number | null | undefined, digits = 2): string {
  return ratio === null || ratio === undefined ? '—' : (ratio * 100).toFixed(digits) + '%'
}

export function signedR(v: number | null | undefined): string {
  return v === null || v === undefined ? '—' : (v > 0 ? '+' : '') + v.toFixed(2) + 'R'
}

/** 中国习惯：红涨绿跌 */
export function trend(v: number | null | undefined): string {
  if (v === null || v === undefined || v === 0) return ''
  return v > 0 ? 'up' : 'down'
}

export function iso(d: Date): string {
  return d.toISOString().slice(0, 10)
}

/** 美东当天日期（用量与跑批都按美东自然日） */
export function todayEt(): string {
  return new Date().toLocaleDateString('en-CA', { timeZone: 'America/New_York' })
}

export function daysAgo(n: number): string {
  return iso(new Date(Date.now() - n * 86_400_000))
}

/** 接口错误带着 {code, message}，比 axios 的 "Request failed with status code 409" 有用。 */
export function errMsg(e: unknown): string {
  const r = (e as { response?: { status?: number; data?: { message?: string } } }).response
  return r?.data?.message ? `${r.status}：${r.data.message}` : String(e)
}
