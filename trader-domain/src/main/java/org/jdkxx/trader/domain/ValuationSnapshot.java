package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 估值快照：券商在某一时刻给出的市值与估值指标。逐日变化，跟着价格走。
 * 亏损股的市盈率为负或缺失都正常，字段一律可空，不要在领域层兜底成 0。
 * 个股与 ETF 的口径不同：ETF 没有市盈率市净率，另有净值与溢价，见 navPerShare / premium。
 *
 * <p>lastPrice 是快照里的当前价（常规时段价）。不落估值表；账户快照给库里没有当日 K 线的持仓估值时用它兜底。
 * 它收盘后冻结在常规时段收盘直到下一个交易日，asOf 却跟着盘后/夜盘更新（实测美东周日 20:52 取 SPY：
 * asOf 是周日、价格 764.29 等于周五 K 线收盘），所以<b>不能用 asOf 判断 lastPrice 属于哪天</b>。
 */
public record ValuationSnapshot(Instrument instrument,
                                Instant asOf,
                                boolean suspended,
                                BigDecimal marketCap,
                                BigDecimal floatMarketCap,
                                Long issuedShares,
                                Long outstandingShares,
                                BigDecimal pe,
                                BigDecimal peTtm,
                                BigDecimal pb,
                                BigDecimal eps,
                                BigDecimal netAssetPerShare,
                                BigDecimal netAsset,
                                BigDecimal netProfit,
                                BigDecimal dividendTtm,
                                BigDecimal dividendYieldTtm,
                                BigDecimal turnoverRate,
                                BigDecimal navPerShare,
                                BigDecimal premium,
                                BigDecimal lastPrice) {
}
