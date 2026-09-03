package org.jdkxx.trader.core.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时跑批（只在 trader.marketdata.schedule-enabled=true 的实例装配；同一台服务器只允许一个实例开启）。
 * 增量：每个交易日收盘后；成分股同步 + 待额度的深度回补：每周六早上。
 */
public class MarketDataScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketDataScheduler.class);

    private final MarketDataFacade facade;

    public MarketDataScheduler(MarketDataFacade facade) {
        this.facade = facade;
    }

    @Scheduled(cron = "${trader.marketdata.increment-cron}", zone = "${trader.marketdata.zone}")
    public void increment() {
        submit("每日增量", () -> facade.increment("SCHEDULE"));
    }

    @Scheduled(cron = "${trader.marketdata.universe.sync-cron}", zone = "${trader.marketdata.zone}")
    public void weekly() {
        submit("成分股同步", () -> facade.syncUniverse("SCHEDULE"));
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
