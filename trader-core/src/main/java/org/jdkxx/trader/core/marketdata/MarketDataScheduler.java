package org.jdkxx.trader.core.marketdata;

import org.jdkxx.trader.core.marketdata.fundamentals.FundamentalsFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时跑批（只在 trader.marketdata.schedule-enabled=true 的实例装配；同一台服务器只允许一个实例开启）。
 * 增量：每个交易日收盘后；估值快照：增量之后 10 分钟；成分股同步：每周六早上；财报：每周六早上七点。
 */
public class MarketDataScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketDataScheduler.class);

    private final MarketDataFacade facade;
    private final FundamentalsFacade fundamentals;

    public MarketDataScheduler(MarketDataFacade facade, FundamentalsFacade fundamentals) {
        this.facade = facade;
        this.fundamentals = fundamentals;
    }

    @Scheduled(cron = "${trader.marketdata.increment-cron}", zone = "${trader.marketdata.zone}")
    public void increment() {
        submit("每日增量", () -> facade.increment("SCHEDULE"));
    }

    @Scheduled(cron = "${trader.marketdata.universe.sync-cron}", zone = "${trader.marketdata.zone}")
    public void weekly() {
        submit("成分股同步", () -> facade.syncUniverse("SCHEDULE"));
    }

    /** 排在每日增量之后：市值与市盈率随价格走，收盘后取到的才是当日值。 */
    @Scheduled(cron = "${trader.marketdata.valuation-cron}", zone = "${trader.marketdata.zone}")
    public void valuation() {
        submit("估值快照", () -> fundamentals.refreshValuation("SCHEDULE"));
    }

    @Scheduled(cron = "${trader.marketdata.financials-cron}", zone = "${trader.marketdata.zone}")
    public void financials() {
        submit("财报刷新", () -> fundamentals.refreshFinancials("SCHEDULE"));
    }

    private void submit(String what, java.util.function.LongSupplier action) {
        try {
            long id = action.getAsLong();
            log.info("定时触发{}，作业 #{}", what, id);
        } catch (RuntimeException e) {
            log.warn("定时触发{}失败：{}", what, e.getMessage());
        }
    }
}
