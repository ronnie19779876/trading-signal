package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 估值快照：券商在某一时刻给出的市值与估值指标。逐日变化，跟着价格走。
 * 亏损股的市盈率为负或缺失都正常，字段一律可空，不要在领域层兜底成 0。
 * 个股与 ETF 的口径不同：ETF 没有市盈率市净率，另有净值与溢价，见 navPerShare / premium。
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
                                BigDecimal premium) {
}
