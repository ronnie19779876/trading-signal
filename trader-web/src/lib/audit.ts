/**
 * 四段审计（日线、基本面、账户、信号）的检查项中文名，与 check-daily.sh 同源的接口共用。
 * 不认识的原样显示。
 */
export const AUDIT_CHECK_LABEL: Record<string, string> = {
  // 日线
  completeness: '完整性', sanity: '合理性', continuity: '连续性', rehab: '复权因子', syncErrors: '同步错误',
  incrementJob: '增量作业', calendarCoverage: '日历覆盖', historyGaps: '历史缺口', phantomBars: '幽灵 K 线',
  unsettledBars: '未落定 K 线', gateway: '网关', calendar: '交易日历',
  // 基本面
  valuationCompleteness: '估值完整性', valuationSanity: '估值合理性', financialsFreshness: '财报新鲜度', valuationJob: '估值作业',
  // 账户
  snapshotExists: '当天快照', reconciliation: '对账', pricing: '估值取价', snapshotJob: '快照作业',
  // 信号
  evaluationExists: '当天评估', coverage: '覆盖', staleData: '数据过期', dataQuality: '数据质量',
  signalConsistency: '信号一致性', aiAnalyses: 'AI 分析', ledgerCurrent: '纸面账本', evaluationJob: '评估作业',
}
export const auditCheckLabel = (name: string) => AUDIT_CHECK_LABEL[name] ?? name
