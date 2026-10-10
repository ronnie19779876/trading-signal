package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.selection.EarningsEvent;
import org.jdkxx.trader.domain.selection.ValuationMetric;
import org.jdkxx.trader.domain.selection.ValuationPoint;
import org.jdkxx.trader.domain.selection.ValuationRange;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 量化选股要的基本面输入（ARCHITECTURE §25.6；富途实现）。只读，都不占订阅额度与历史 K 线额度。
 * 单独成一个端口，不往 {@link MarketDataGateway} 里加：量化选股是独立功能。
 */
public interface SelectionDataGateway {

    /** 只要季报（不混年报），一次一只，{@code periods} 上限 50（实测一页 50 季约回到 2014）。 */
    CompletableFuture<List<FinancialReport>> quarterlyFinancials(Instrument instrument, FinancialStatement statement, int periods);

    /** 逐日估值历史，按日期从早到晚。 */
    CompletableFuture<List<ValuationPoint>> valuationHistory(Instrument instrument, ValuationMetric metric, ValuationRange range);

    /** 财报公告日（实测最近 20 季，含未来预定日期）。 */
    CompletableFuture<List<EarningsEvent>> earningsHistory(Instrument instrument);
}
