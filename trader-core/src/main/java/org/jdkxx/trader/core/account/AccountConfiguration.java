package org.jdkxx.trader.core.account;

import org.jdkxx.trader.core.gateway.GatewayRegistry;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.AccountGateway;
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
 */
@Configuration
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
@EnableConfigurationProperties(AccountProperties.class)
public class AccountConfiguration {

    @Bean
    public AccountSnapshotService accountSnapshotService(AccountProperties props, MarketDataProperties marketData, Environment env,
                                                         GatewayRegistry gateways, AccountGateway accounts, MarketDataGateway market,
                                                         InstrumentRepository instruments, DailyBarRepository bars, PoolRepository pool,
                                                         TradingDayRepository days, AccountSnapshotRepository snapshots) {
        return new AccountSnapshotService(props, env.getProperty("trader.ibkr.account"), gateways.require(Broker.IBKR), accounts,
                market, instruments, bars, pool, days, snapshots, Clock.systemUTC(), ZoneId.of(marketData.zone()));
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
}
