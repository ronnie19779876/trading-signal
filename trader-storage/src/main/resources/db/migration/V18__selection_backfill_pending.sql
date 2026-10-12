-- 3.2.1：回补状态加 PENDING（待重试）。「富途还没有日 K」这类暂时取不了的不算完成、也不算失败，下次回补重试。
-- 3.2.0 把它记成 EMPTY，被当成已完成永久跳过（生产 SKYD、TWLO、VYLR，成分股同步新进、日 K 还没取）。
ALTER TABLE selection_backfill_state DROP CONSTRAINT selection_backfill_state_status_check;
ALTER TABLE selection_backfill_state ADD CONSTRAINT selection_backfill_state_status_check
    CHECK (status IN ('OK', 'EMPTY', 'SKIPPED', 'FAILED', 'PENDING'));
-- 已经误记成 EMPTY 的改回 PENDING，让下一次回补重试
UPDATE selection_backfill_state SET status = 'PENDING', updated_at = now()
WHERE part = 'PRICES' AND status = 'EMPTY' AND detail LIKE '富途还没有日 K%';
COMMENT ON COLUMN selection_backfill_state.status IS 'EMPTY = 券商回了空（不是失败）；SKIPPED = 不需要；PENDING = 暂时取不了、下次重试（如富途日 K 还没到）';
