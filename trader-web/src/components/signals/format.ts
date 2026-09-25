import type { Gate, Outcome, SignalStatus, EvaluationStatus, Role, TrackStatus, ExitReason } from '../../api/signals'
import type { Stance, Confidence, AiStatus, AiVerdict } from '../../api/ai'

// 数字与日期的格式化全站一份，见 lib/format.ts；这里只留信号相关的中文标签。
// 仍然从本文件转出，是为了让信号页的组件只 import 一个地方。
export { errMsg, num, pct, signedR, trend, daysAgoEt as daysAgo, isoEt as iso, todayEt } from '../../lib/format'

type TagType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

export const GATE_LABEL: Record<Gate, string> = { TREND: '趋势', LOCATION: '定位', TRIGGER: '触发', RISK: '风控' }
export const GATES: Gate[] = ['TREND', 'LOCATION', 'TRIGGER', 'RISK']

/**
 * 展示用：含 PENDING_AI。它是<b>回放接口里的中间态</b>，落库的评估行永远不会是这个值。
 * 所以筛选下拉不能按这张表全量生成——选中「待 AI」必定零结果
 * （2026-09-25 全项目审查发现）。筛选用 {@link OUTCOME_FILTERS}。
 */
export const OUTCOME_LABEL: Record<Outcome | 'PENDING_AI', string> = {
  SIGNAL: '信号', NO_SIGNAL: '未触发', SUPPRESSED_EDGE: '边沿抑制', SUPPRESSED_COOLDOWN: '冷却抑制', BLOCKED_BY_AI: 'AI 否决', PENDING_AI: '待 AI',
}
/** 真的会落库、因而能筛出东西的结果。 */
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
