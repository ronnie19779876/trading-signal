package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.CompanyProfile;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.HistoryQuota;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentStatic;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.domain.SubscriptionInfo;
import org.jdkxx.trader.domain.TradingDay;
import org.jdkxx.trader.domain.ValuationSnapshot;

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

    // ------------------------------------------------------------------ 实时报价（步骤 2）

    /** 订阅基础报价并注册推送；订阅成功后券商会立即推一条当前值。每只占 1 个订阅额度。 */
    CompletableFuture<Void> subscribeQuotes(List<Instrument> instruments);

    /** 订阅满 1 分钟才允许反订阅。 */
    CompletableFuture<Void> unsubscribeQuotes(List<Instrument> instruments);

    /** 全部连接合计的订阅额度。 */
    CompletableFuture<SubscriptionInfo> subscriptionInfo();

    void addQuoteListener(QuoteListener listener);

    // ------------------------------------------------------------------ 基本面（步骤 3）

    /**
     * 估值快照，无需订阅，不占订阅与历史 K 线额度。一次最多 {@link #SNAPSHOT_BATCH} 只，超出由调用方分批。
     * 市值随价格变动，取数时点决定这批数字属于盘中还是收盘。
     */
    CompletableFuture<List<ValuationSnapshot>> snapshots(List<Instrument> instruments);

    /** 一只标的一类报表的最近若干期，按期末从新到旧。 */
    CompletableFuture<List<FinancialReport>> financials(Instrument instrument, FinancialStatement statement, int periods);

    CompletableFuture<CompanyProfile> companyProfile(Instrument instrument);

    /** 券商对快照接口的单次上限。 */
    int SNAPSHOT_BATCH = 400;
}
