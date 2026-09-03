package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.HistoryQuota;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentStatic;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.domain.TradingDay;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 行情数据端口（本期只有富途实现）。所有方法都是只读或只影响订阅额度；K 线一律不复权。
 * 两条拉 K 线的通道：
 * <ul>
 *   <li>{@link #historyDailyBars}：历史接口，最多 20 年，分页在适配器内完成，<b>每只占 1 个 7 天历史额度</b>；</li>
 *   <li>{@link #subscribeDailyBars} + {@link #recentDailyBars}：订阅后取最近 ≤1000 根，不占历史额度，只占订阅额度。</li>
 * </ul>
 */
public interface MarketDataGateway {

    CompletableFuture<List<InstrumentStatic>> staticInfo(List<Instrument> instruments);

    CompletableFuture<HistoryQuota> historyQuota();

    CompletableFuture<List<DailyBar>> historyDailyBars(Instrument instrument, LocalDate from, LocalDate to);

    CompletableFuture<Void> subscribeDailyBars(List<Instrument> instruments);

    /** 订阅满 1 分钟才允许反订阅，过早调用会被券商拒绝。 */
    CompletableFuture<Void> unsubscribeDailyBars(List<Instrument> instruments);

    CompletableFuture<List<DailyBar>> recentDailyBars(Instrument instrument, int count);

    CompletableFuture<List<RehabFactor>> rehab(Instrument instrument);

    CompletableFuture<List<TradingDay>> tradingDays(Market market, LocalDate from, LocalDate to);
}
