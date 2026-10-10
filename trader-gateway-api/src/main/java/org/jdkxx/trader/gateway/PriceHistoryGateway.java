package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.selection.SplitAdjustedBar;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 历史日线端口（量化选股，ARCHITECTURE §25.6；盈透实现）。只读。
 * <p>返回<b>按拆股调整</b>的 TRADES 日线（盈透口径，截至请求当天），不进 {@code daily_bar}。
 * 盈透历史数据有节奏限制（10 分钟 60 次），实现方自行限流，调用方线程可能被阻塞数分钟，不得在 SDK 回调线程上调用。
 */
public interface PriceHistoryGateway {

    /**
     * @param conId 盈透合约 id（先用 {@link ReferenceDataGateway#lookup} 解析）
     * @param from  起始日（含）
     * @param to    截止日（含）
     */
    CompletableFuture<List<SplitAdjustedBar>> splitAdjustedDailyBars(long conId, LocalDate from, LocalDate to);
}
