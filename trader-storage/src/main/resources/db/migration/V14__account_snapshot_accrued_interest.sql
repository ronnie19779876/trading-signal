-- 账户快照补上「应计利息」（盈透 $LEDGER-AccruedCash）。
--
-- 资金恒等式原先是三项：现金 + 股票市值 + 应计股息 = 净值。此前现金余额小、应计利息一直是 0，
-- 三项相加正好成立，连续 8 次快照都判 OK；2026-09-28 现金涨到两万多之后它变成 1.49，
-- 等式差的就是这一项——当时误判成对账 WARN、作业记 PARTIAL、jobs 健康降级，而数据本身分毫不差。
--
-- 历史行留 NULL：那些天的原始标签都还在 raw 里，要复盘就从 raw 取；不回填，避免把推断值当实测值。
ALTER TABLE account_snapshot ADD COLUMN accrued_interest numeric(20, 4);

COMMENT ON COLUMN account_snapshot.accrued_interest IS '应计利息（盈透 $LEDGER-AccruedCash）；3.1.3 起采集，更早的快照为 NULL，原值仍在 raw 里';
