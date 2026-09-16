-- 第 4 期信号判定的结构口径要单独拿股数变动比例（成交量按股数调整）。
-- 实测（FutuShareActionsIT，2026-09-17）：拆股、合股、送股都带 base/ert；合股与分拆可同在一个事件里（HON 2026-06-29 flag=258），
-- 这时 fwd_a 是混合比例，不能拿来调成交量。
ALTER TABLE rehab_factor
    ADD COLUMN join_base      integer NOT NULL DEFAULT 0,
    ADD COLUMN join_ert       integer NOT NULL DEFAULT 0,
    ADD COLUMN bonus_base     integer NOT NULL DEFAULT 0,
    ADD COLUMN bonus_ert      integer NOT NULL DEFAULT 0,
    ADD COLUMN transfer_base  integer NOT NULL DEFAULT 0,
    ADD COLUMN transfer_ert   integer NOT NULL DEFAULT 0;
COMMENT ON COLUMN rehab_factor.join_base IS '合股比例 base:ert，3:1 表示 3 股合 1 股（除权前价格 ×3）';
COMMENT ON COLUMN rehab_factor.bonus_base IS '送股比例 base:ert，每 base 股送 ert 股（除权前价格 × base/(base+ert)）';

-- 已有的合股 / 送股 / 转增事件比例还是 0：让这些标的在下一次增量里优先重拉因子（到期名单按 NULLS FIRST，开发库实测 32 只）。
-- 重拉之前，信号判定遇到比例缺失的事件会判为不予判定，不会用错的成交量。
UPDATE bar_sync_state SET rehab_fetched_at = NULL
WHERE instrument_id IN (SELECT DISTINCT instrument_id FROM rehab_factor WHERE company_act_flag & 14 <> 0);
