package org.jdkxx.trader.core.marketdata;

import org.jdkxx.trader.common.ratelimit.Sleeper;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService;
import org.jdkxx.trader.core.marketdata.bars.BarQueryService;
import org.jdkxx.trader.core.marketdata.bars.DailyIncrementService;
import org.jdkxx.trader.core.marketdata.bars.DeepBackfillService;
import org.jdkxx.trader.core.marketdata.bars.RotationRefresher;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.core.marketdata.quotes.QuoteCache;
import org.jdkxx.trader.core.marketdata.quotes.QuoteStreamService;
import org.jdkxx.trader.core.marketdata.quotes.QuoteSubscriptionService;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.core.marketdata.universe.SpyHoldingsCrossCheck;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.core.marketdata.universe.UniverseSource;
import org.jdkxx.trader.core.marketdata.universe.UniverseSyncService;
import org.jdkxx.trader.core.marketdata.universe.WikipediaUniverseSource;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.BarSyncStateRepository;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.PoolRepository;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 行情数据底座的装配。整块只在存储启用时存在（仓储都依赖数据库）。
 */
@Configuration
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
@EnableConfigurationProperties(MarketDataProperties.class)
public class MarketDataConfiguration {

    @Bean
    public UniverseSource universeSource(MarketDataProperties props) {
        return new WikipediaUniverseSource(props.universe());
    }

    @Bean
    public SpyHoldingsCrossCheck spyHoldingsCrossCheck(MarketDataProperties props) {
        return new SpyHoldingsCrossCheck(props.universe());
    }

    @Bean
    public InstrumentDirectory instrumentDirectory(InstrumentRepository instruments) {
        return new InstrumentDirectory(instruments);
    }

    @Bean
    public UniverseScope universeScope(IndexConstituentRepository constituents, PoolRepository pool, InstrumentRepository instruments) {
        return new UniverseScope(constituents, pool, instruments);
    }

    @Bean
    public JobService jobService(JobRunRepository repo) {
        return new JobService(repo);
    }

    @Bean
    public UniverseSyncService universeSyncService(MarketDataProperties props, UniverseSource source, SpyHoldingsCrossCheck spy,
                                                   InstrumentRepository instruments, IndexConstituentRepository constituents,
                                                   MarketDataGateway gateway) {
        return new UniverseSyncService(props, source, spy, instruments, constituents, gateway);
    }

    @Bean
    public RotationRefresher rotationRefresher(MarketDataProperties props, MarketDataGateway gateway, DailyBarRepository bars,
                                               BarSyncStateRepository states) {
        return new RotationRefresher(props.refresh(), gateway, bars, states, Sleeper.REAL);
    }

    @Bean
    public DeepBackfillService deepBackfillService(MarketDataProperties props, MarketDataGateway gateway, DailyBarRepository bars,
                                                   RehabFactorRepository rehabs, BarSyncStateRepository states, UniverseScope scope) {
        return new DeepBackfillService(props, gateway, bars, rehabs, states, scope);
    }

    @Bean
    public DailyIncrementService dailyIncrementService(MarketDataProperties props, MarketDataGateway gateway, TradingDayRepository days,
                                                       DailyBarRepository bars, UniverseScope scope, RotationRefresher rotation,
                                                       DeepBackfillService deep) {
        return new DailyIncrementService(props, gateway, days, bars, scope, rotation, deep, Clock.systemUTC());
    }

    @Bean
    public BarQueryService barQueryService(InstrumentDirectory directory, DailyBarRepository bars, RehabFactorRepository rehabs,
                                           MarketDataProperties props) {
        return new BarQueryService(directory, bars, rehabs, props);
    }

    @Bean
    public BarAuditService barAuditService(MarketDataProperties props, UniverseScope scope, DailyBarRepository bars,
                                           TradingDayRepository days, BarSyncStateRepository states, JobRunRepository jobs,
                                           MarketDataGateway gateway) {
        return new BarAuditService(props, scope, bars, days, states, jobs, gateway, Clock.systemUTC());
    }

    @Bean
    public PoolService poolService(MarketDataProperties props, PoolRepository pool, InstrumentDirectory directory, JobService jobs,
                                   DeepBackfillService deep, InstrumentRepository instruments, MarketDataGateway gateway) {
        return new PoolService(props, pool, directory, jobs, deep, instruments, gateway);
    }

    @Bean
    public MarketDataFacade marketDataFacade(MarketDataProperties props, JobService jobs, UniverseSyncService sync, UniverseScope scope,
                                             RotationRefresher rotation, DeepBackfillService deep, DailyIncrementService increment,
                                             InstrumentRepository instruments, IndexConstituentRepository constituents,
                                             DailyBarRepository bars, BarSyncStateRepository states, MarketDataGateway gateway,
                                             InstrumentDirectory directory) {
        return new MarketDataFacade(props, jobs, sync, scope, rotation, deep, increment, instruments, constituents, bars, states,
                gateway, directory);
    }

    @Bean
    @ConditionalOnProperty(name = "trader.marketdata.schedule-enabled", havingValue = "true")
    public MarketDataScheduler marketDataScheduler(MarketDataFacade facade) {
        return new MarketDataScheduler(facade);
    }

    // ------------------------------------------------------------------ 实时报价（步骤 2，不落库）

    @Bean
    public QuoteCache quoteCache(MarketDataGateway gateway) {
        QuoteCache cache = new QuoteCache();
        gateway.addQuoteListener(cache::accept);
        return cache;
    }

    @Bean
    public QuoteSubscriptionService quoteSubscriptionService(MarketDataProperties props, MarketDataGateway gateway, UniverseScope scope,
                                                             QuoteCache cache, RotationRefresher rotation, PoolService pool) {
        QuoteSubscriptionService service = new QuoteSubscriptionService(props.realtime(), gateway, scope, cache, Clock.systemUTC());
        if (gateway instanceof BrokerGateway broker) {
            broker.addListener(service);
        }
        rotation.coordinator(service);
        pool.afterChange(service::reconcile);
        return service;
    }

    @Bean(destroyMethod = "close")
    public QuoteStreamService quoteStreamService(MarketDataProperties props, QuoteCache cache, QuoteSubscriptionService subscriptions) {
        return new QuoteStreamService(cache, subscriptions::status, props.realtime().streamInterval());
    }
}
