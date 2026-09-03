package org.jdkxx.trader.core.marketdata.bars;

/**
 * 复权因子的语义：CUMULATIVE = 券商给的每条因子已累计"从该除权日到今天"的全部事件，取离目标日最近的一条即可；
 * PER_EVENT = 每条只是该事件自身的比例，需要把目标日之后的事件按时间顺序复合。
 * 实测（FutuMarketDataIT，AAPL 2026-04-20～05-20，跨两次除息）：PER_EVENT 与富途前复权序列最大相对误差 1.8e-5，
 * CUMULATIVE 为 8.5e-4——富途的每条因子只是该事件自身的比例，必须逐事件复合。默认 PER_EVENT。
 */
public enum FactorMode {
    CUMULATIVE, PER_EVENT
}
