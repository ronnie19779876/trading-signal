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
        @DefaultValue("false") boolean scheduleEnabled,
        @DefaultValue("0 30 17 * * MON-FRI") String incrementCron,
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
}
