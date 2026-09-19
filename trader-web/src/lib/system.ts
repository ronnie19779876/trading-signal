import type { GatewayState, EventView } from '../api/gateways'
import type { JobRun } from '../api/marketdata'

/** 系统页用到的中文标签：作业、触发方式、作业状态、网关状态、连接事件。后端给的是英文常量。 */

type TagType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

/** 作业类型（与后端 Jobs 常量一一对应）；不认识的原样显示 */
export const JOB_LABEL: Record<string, string> = {
  DAILY_INCREMENT: '每日增量',
  VALUATION_SNAPSHOT: '估值快照',
  ACCOUNT_SNAPSHOT: '账户快照',
  SIGNAL_EVALUATION: '信号评估',
  CATCHUP_CHECK: '补偿检查',
  UNIVERSE_SYNC: '成分股同步',
  FINANCIALS_REFRESH: '财报刷新',
  UNIVERSE_REFRESH: '全量轮转拉 K 线',
  DEEP_BACKFILL: '深度回补',
  REHAB_REFRESH: '复权因子刷新',
  CALENDAR_BACKFILL: '交易日历回补',
}
export const jobLabel = (job: string) => JOB_LABEL[job] ?? job

export const TRIGGER_LABEL: Record<string, string> = { SCHEDULE: '定时', MANUAL: '手动', CATCHUP: '补跑' }
export const triggerLabel = (t: string) => TRIGGER_LABEL[t] ?? t

export const JOB_STATUS: Record<JobRun['status'], { text: string; type: TagType }> = {
  RUNNING: { text: '运行中', type: 'primary' },
  OK: { text: '成功', type: 'success' },
  PARTIAL: { text: '部分成功', type: 'warning' },
  FAILED: { text: '失败', type: 'danger' },
  SKIPPED: { text: '跳过', type: 'info' },
}

export const GATEWAY_STATE: Record<GatewayState, { text: string; type: TagType }> = {
  CONNECTED: { text: '已连接', type: 'success' },
  CONNECTING: { text: '连接中', type: 'warning' },
  RECONNECTING: { text: '重连中', type: 'warning' },
  DISCONNECTED: { text: '已断开', type: 'warning' },
  DISABLED: { text: '未启用', type: 'info' },
  ERROR: { text: '出错', type: 'danger' },
}

export const EVENT_LABEL: Record<EventView['event'], { text: string; type: TagType }> = {
  CONNECTED: { text: '连上', type: 'success' },
  RECONNECTED: { text: '重连成功', type: 'success' },
  DISCONNECTED: { text: '断开', type: 'warning' },
  ERROR: { text: '出错', type: 'danger' },
}

/** "3 分钟前"这类相对时间；超过一天给天数。 */
export function ago(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return '—'
  const s = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000))
  if (s < 60) return `${s} 秒前`
  if (s < 3600) return `${Math.floor(s / 60)} 分钟前`
  if (s < 86400) return `${Math.floor(s / 3600)} 小时 ${Math.floor((s % 3600) / 60)} 分前`
  return `${Math.floor(s / 86400)} 天前`
}

/** 作业耗时（秒 / 分）。 */
export function duration(from: string, to: string | null): string {
  if (!to) return '—'
  const s = Math.round((new Date(to).getTime() - new Date(from).getTime()) / 1000)
  return s < 60 ? `${s} 秒` : `${Math.floor(s / 60)} 分 ${s % 60} 秒`
}
