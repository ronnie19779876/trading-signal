package org.jdkxx.trader.domain;

/** 标的池角色：POOL 手工维护的候选池（上限见配置）；HOLDING 持仓（第 3 期起由盈透持仓自动维护）。 */
public enum PoolRole {
    POOL, HOLDING
}
