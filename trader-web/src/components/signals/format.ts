import type { Gate, Outcome, SignalStatus, EvaluationStatus, Role, TrackStatus, ExitReason } from '../../api/signals'
import type { Stance, Confidence, AiStatus, AiVerdict } from '../../api/ai'

// Number and date formatting has a single site-wide copy in lib/format.ts; only the signal-specific Chinese labels
// live here. They are still re-exported from this file so the signals components import from one place only.
export { errMsg, num, pct, signedR, trend, daysAgoEt as daysAgo, isoEt as iso, todayEt } from '../../lib/format'

type TagType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

export const GATE_LABEL: Record<Gate, string> = { TREND: '趋势', LOCATION: '定位', TRIGGER: '触发', RISK: '风控' }
export const GATES: Gate[] = ['TREND', 'LOCATION', 'TRIGGER', 'RISK']

/**
 * For display: includes PENDING_AI. That is an <b>intermediate state of the replay endpoint</b>; a stored
 * evaluation row never has this value. So the filter dropdown must not be generated from this whole table --
 * selecting "pending AI" would always return zero rows (found in the 2026-09-25 full-project review). Filters use
 * {@link OUTCOME_FILTERS}.
 */
export const OUTCOME_LABEL: Record<Outcome | 'PENDING_AI', string> = {
  SIGNAL: '信号', NO_SIGNAL: '未触发', SUPPRESSED_EDGE: '边沿抑制', SUPPRESSED_COOLDOWN: '冷却抑制', BLOCKED_BY_AI: 'AI 否决', PENDING_AI: '待 AI',
}
/** Outcomes that are actually stored and can therefore match a filter. */
export const OUTCOME_FILTERS: Outcome[] = ['SIGNAL', 'NO_SIGNAL', 'SUPPRESSED_EDGE', 'SUPPRESSED_COOLDOWN', 'BLOCKED_BY_AI']
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
export const STANCE_TYPE: Record<Stance, TagType> = { BULLISH: 'success', NEUTRAL: 'info', BEARISH: 'danger', AVOID: 'warning' }
export const CONFIDENCE_LABEL: Record<Confidence, string> = { HIGH: '把握高', MEDIUM: '把握中', LOW: '把握低' }
export const AI_STATUS_LABEL: Record<AiStatus, string> = {
  OK: '成功', REFUSED: '拒答', TRUNCATED: '截断', INVALID: '结构非法', FAILED: '调用失败', SKIPPED_BUDGET: '预算跳过', FAILED_DATA: '输入构建失败',
}
export const VERDICT_LABEL: Record<AiVerdict, string> = { VETO: '否决', ALLOW: '放行', ABSENT: '没有结论' }
