package org.jdkxx.trader.domain;

/**
 * 标的池角色。
 * <ul>
 *   <li>{@code POOL} 手工维护的候选池（上限见配置，只有这个角色受上限约束）；</li>
 *   <li>{@code HOLDING} 持仓（第 3 期起由盈透持仓自动维护）；</li>
 *   <li>{@code BENCHMARK} 基准（如纳指 100 ETF）：照常采集日 K、复权、实时订阅，
 *       但<b>不参与选股</b>——信号阶段扫描候选时要用 {@code candidates()} 而不是 {@code poolAndHoldings()}。</li>
 * </ul>
 */
public enum PoolRole {
    POOL, HOLDING, BENCHMARK
}
