-- 纸面账本的「当前价」与浮动盈亏（3.1.4）。
-- 价格与 entry_price 同为**判定日口径**（整段结构换算后折回信号日尺度），
-- 由 SignalTrades.simulate 用同一个 scale 折回——持有期间一旦拆股，口径不一致的两个价就不可比。
-- last_close_date 可能早于 updated_through：标的停牌或缺 K 线时当前价会滞后（2026-10 的 WBD 即如此），
-- 所以展示当前价必须连这个日期一起给，否则陈旧价看起来像今天的。
-- 只给未平仓（OPEN）的行填；待入场还没有成本，已平仓有 exit_price。
ALTER TABLE signal_track
    ADD COLUMN last_close      numeric(18, 6),
    ADD COLUMN last_close_date date,
    ADD COLUMN unrealized_r    numeric(18, 6);

COMMENT ON COLUMN signal_track.last_close IS '模拟走到的最后一根 K 线的收盘（判定日口径）；只给 OPEN 填';
COMMENT ON COLUMN signal_track.last_close_date IS '上面那根 K 线的日期，可能早于 updated_through（停牌/缺 K 线）';
COMMENT ON COLUMN signal_track.unrealized_r IS '浮动盈亏 ÷ R，R = 判定日收盘 − 本变体止损，故两个变体通常不同';
