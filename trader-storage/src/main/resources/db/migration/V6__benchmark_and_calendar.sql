-- 第 2 期·步骤 4：基准标的角色 + 交易日历来源标记。

-- 基准（如纳指 100 ETF）要跟着采集日 K、复权、实时订阅，但不是候选买入标的：
-- 混进 POOL 会让信号阶段把基准当成候选，这类错误很隐蔽，所以单列一个角色。
ALTER TABLE pool_member DROP CONSTRAINT pool_member_role_check;
ALTER TABLE pool_member ADD CONSTRAINT pool_member_role_check
    CHECK (role IN ('POOL', 'HOLDING', 'BENCHMARK'));
COMMENT ON TABLE pool_member IS '标的池：POOL 手工候选池（上限见配置）、HOLDING 持仓（第 3 期由盈透自动维护）、BENCHMARK 基准（只采集不参与选股）';

-- 券商的交易日历只能回到 2016-09（实测请求 21 年与 27 年返回完全相同，都是 2591 天），
-- 更早的从已有日 K 线反推：池/持仓/基准都是大盘股，每个交易日都有成交，
-- 它们出现过的 trade_date 并集就是那段时间的交易日。
ALTER TABLE trading_day ADD COLUMN source varchar(8) NOT NULL DEFAULT 'FUTU'
    CHECK (source IN ('FUTU', 'DERIVED'));
COMMENT ON COLUMN trading_day.source IS 'FUTU=券商给的（约 2016-09 起）；DERIVED=从日 K 线反推（更早，券商取不到）';
CREATE INDEX trading_day_source_idx ON trading_day (market, source);
