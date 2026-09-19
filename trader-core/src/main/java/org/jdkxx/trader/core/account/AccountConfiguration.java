package org.jdkxx.trader.core.account;

import org.jdkxx.trader.core.gateway.GatewayRegistry;
import org.jdkxx.trader.core.marketdata.MarketDataFacade;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.PoolService;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.AccountGateway;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.GatewayState;
import org.jdkxx.trader.gateway.LiveAccountGateway;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.account.AccountSnapshotRepository;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.PoolRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 账户与持仓（第 3 期）的装配。整块只在存储启用时存在。
 * 选哪个盈透账户沿用 {@code trader.ibkr.account}（没配且只有一个受管账户就用它）。
 * 自动触发的部分（定时快照、盈透连上后同步持仓）只在开了跑批的实例装配：开发实例默认关。
 */
@Configuration
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
@EnableConfigurationProperties(AccountProperties.class)
public class AccountConfiguration {

    @Bean(destroyMethod = "close")
    public HoldingSyncService holdingSyncService(AccountProperties props, Environment env, GatewayRegistry gateways,
                                                 AccountGateway accounts, InstrumentRepository instruments, PoolRepository pool,
                                                 PoolService poolService, MarketDataFacade marketData, JobRunRepository jobRuns) {
        return new HoldingSyncService(props, env.getProperty("trader.ibkr.account"), gateways.require(Broker.IBKR), accounts,
                instruments, pool, poolService, marketData, jobRuns);
    }

    @Bean
    public AccountSnapshotService accountSnapshotService(AccountProperties props, MarketDataProperties marketData, Environment env,
                                                         GatewayRegistry gateways, AccountGateway accounts, MarketDataGateway market,
                                                         InstrumentRepository instruments, DailyBarRepository bars, PoolRepository pool,
                                                         TradingDayRepository days, AccountSnapshotRepository snapshots,
                                                         HoldingSyncService holdingSync) {
        return new AccountSnapshotService(props, env.getProperty("trader.ibkr.account"), gateways.require(Broker.IBKR), accounts,
                market, instruments, bars, pool, days, snapshots, holdingSync, Clock.systemUTC(), ZoneId.of(marketData.zone()));
    }

    /** 实时账户（3.0.2）：按需订阅，开发与生产实例都装配（订阅按客户端计，互不干扰，2026-09-19 实测）。 */
    @Bean(destroyMethod = "close")
    public LiveAccountService liveAccountService(AccountProperties props, Environment env, GatewayRegistry gateways,
                                                 LiveAccountGateway live) {
        return new LiveAccountService(props, env.getProperty("trader.ibkr.account"), gateways.require(Broker.IBKR), live,
                Clock.systemUTC());
    }

    @Bean
    public AccountAuditService accountAuditService(AccountSnapshotRepository snapshots, TradingDayRepository days,
                                                   JobRunRepository jobRuns, MarketDataProperties marketData) {
        return new AccountAuditService(snapshots, days, jobRuns, Clock.systemUTC(), ZoneId.of(marketData.zone()));
    }

    @Bean
    public AccountFacade accountFacade(JobService jobs, AccountSnapshotService service, AccountSnapshotRepository snapshots,
                                       TradingDayRepository days, MarketDataProperties marketData, Environment env) {
        return new AccountFacade(jobs, service, snapshots, days, env.getProperty("trader.environment"), Clock.systemUTC(),
                ZoneId.of(marketData.zone()));
    }

    @Bean
    @ConditionalOnExpression("${trader.marketdata.schedule-enabled:false} and ${trader.account.enabled:true}")
    public AccountScheduler accountScheduler(AccountFacade facade, AccountSnapshotRepository snapshots, TradingDayRepository days,
                                             JobRunRepository jobRuns, MarketDataProperties marketData) {
        return new AccountScheduler(facade, snapshots, days, jobRuns, Clock.systemUTC(), ZoneId.of(marketData.zone()));
    }

    /** 盈透连上后同步一次持仓。装配时网关若已连上（自动连接先于本 bean 完成），立刻补一次。 */
    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("${trader.marketdata.schedule-enabled:false} and ${trader.account.enabled:true}")
    public HoldingSyncOnConnect holdingSyncOnConnect(HoldingSyncService sync, GatewayRegistry gateways) {
        HoldingSyncOnConnect listener = new HoldingSyncOnConnect(sync);
        BrokerGateway ibkr = gateways.require(Broker.IBKR);
        ibkr.addListener(listener);
        if (ibkr.status().state() == GatewayState.CONNECTED) {
            listener.onConnected(Broker.IBKR, false);
        }
        return listener;
    }
}
