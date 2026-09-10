package org.jdkxx.trader.core.marketdata;

import org.jdkxx.trader.core.marketdata.bars.FactorMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.LocalDate;

/**
 * 行情数据底座配置（{@code trader.marketdata.*}）。机制参数放 jar 内默认值，开关（schedule-enabled）在外置配置。
 */
@ConfigurationProperties(prefix = "trader.marketdata")
public record MarketDataProperties(
        @DefaultValue Universe universe,
        @DefaultValue Pool pool,
        @DefaultValue Refresh refresh,
        @DefaultValue History history,
        @DefaultValue Adjust adjust,
        @DefaultValue Realtime realtime,
        @DefaultValue Fundamentals fundamentals,
        @DefaultValue("false") boolean scheduleEnabled,
        @DefaultValue("0 30 17 * * MON-FRI") String incrementCron,
        @DefaultValue("0 40 17 * * MON-FRI") String valuationCron,
        @DefaultValue("0 0 7 * * SAT") String financialsCron,
        @DefaultValue("0 0 21 * * MON-FRI") String catchupCron,
        @DefaultValue("America/New_York") String zone) {

    public record Universe(
            @DefaultValue("https://en.wikipedia.org/wiki/List_of_S%26P_500_companies") String wikipediaSp500Url,
            @DefaultValue("https://en.wikipedia.org/wiki/List_of_NASDAQ-100_companies") String wikipediaNdx100Url,
            @DefaultValue("https://www.ssga.com/us/en/intermediary/library-content/products/fund-data/etfs/us/holdings-daily-us-en-spy.xlsx") String spyHoldingsUrl,
            @DefaultValue("true") boolean crossCheckSpy,
            @DefaultValue("0 30 6 * * SAT") String syncCron,
            @DefaultValue("200") int staticBatchSize,
            @DefaultValue("trading-signal/1.0 (market data research; contact via repository)") String userAgent) {
    }

    public record Pool(@DefaultValue("50") int maxSize) {
    }

    /**
     * 订阅轮转：每批订阅 batchSize 只（≤ 订阅额度 − 预留），批内至少停留 holdSeconds 秒才反订阅（券商规则：满 1 分钟）。
     */
    public record Refresh(
            @DefaultValue("90") int batchSize,
            @DefaultValue("65") int holdSeconds,
            @DefaultValue("true") boolean universeIncrement,
            @DefaultValue("1000") int fullCount,
            @DefaultValue("5") int overlap) {
    }

    public record History(@DefaultValue("2006-01-01") LocalDate from, @DefaultValue("10") int quotaReserve) {
    }

    public record Adjust(@DefaultValue("PER_EVENT") FactorMode factorMode) {
    }

    /**
     * 实时报价（不落库）。auto-subscribe：开发机 false / 发布包 true——两个实例同时订会占双份额度。
     * pause-during-refresh：全量轮转期间暂停实时订阅（释放额度给 90 只一批的轮转），结束后恢复。
     */
    /**
     * 基本面。估值快照全量每交易日一次（一次 400 只，不占订阅与历史额度）；
     * 财报只给池与持仓，每周一次——全量做要 35 分钟且绝大多数标的用不上。
     */
    public record Fundamentals(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("400") int snapshotBatchSize,
            @DefaultValue("12") int financialPeriods,
            @DefaultValue("30d") java.time.Duration profileRefreshAfter,
            @DefaultValue("180d") java.time.Duration financialStaleAfter) {
    }

    public record Realtime(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("false") boolean autoSubscribe,
            @DefaultValue("10") int reserveQuota,
            @DefaultValue("true") boolean pauseDuringRefresh,
            @DefaultValue("1s") java.time.Duration streamInterval,
            @DefaultValue("61s") java.time.Duration unsubscribeMinAge) {
    }
}
