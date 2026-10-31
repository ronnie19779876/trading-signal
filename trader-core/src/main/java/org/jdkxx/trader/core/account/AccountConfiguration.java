package org.jdkxx.trader.core.account;

import java.time.Clock;
import java.time.ZoneId;
import org.jdkxx.trader.core.gateway.GatewayRegistry;
import org.jdkxx.trader.core.marketdata.MarketDataFacade;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.PoolService;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.core.marketdata.universe.UnknownSymbolGuard;
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
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.selection.SelectionEarningsRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wiring of account and positions (phase 3). The whole block only exists when storage is enabled.
 * Which IBKR account to use follows {@code trader.ibkr.account} (when unset and there is exactly
 * one managed account, that one). The automatically triggered parts (scheduled snapshot, holding
 * sync after IBKR connects) are only wired on instances with scheduling enabled; the dev instance
 * has them off by default. Every service gets a UTC clock plus the market zone ({@code
 * trader.marketdata.zone}) for calendar dates.
 */
@Configuration
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
@EnableConfigurationProperties({AccountProperties.class, HoldingReviewProperties.class})
public class AccountConfiguration {

  @Bean(destroyMethod = "close")
  public HoldingSyncService holdingSyncService(
      AccountProperties props,
      Environment env,
      GatewayRegistry gateways,
      AccountGateway accounts,
      InstrumentRepository instruments,
      PoolRepository pool,
      PoolService poolService,
      MarketDataFacade marketData,
      JobRunRepository jobRuns) {
    return new HoldingSyncService(
        props,
        env.getProperty("trader.ibkr.account"),
        gateways.require(Broker.IBKR),
        accounts,
        instruments,
        pool,
        poolService,
        marketData,
        jobRuns);
  }

  @Bean
  public AccountSnapshotService accountSnapshotService(
      AccountProperties props,
      MarketDataProperties marketData,
      Environment env,
      GatewayRegistry gateways,
      AccountGateway accounts,
      MarketDataGateway market,
      InstrumentRepository instruments,
      DailyBarRepository bars,
      PoolRepository pool,
      TradingDayRepository days,
      AccountSnapshotRepository snapshots,
      HoldingSyncService holdingSync,
      UnknownSymbolGuard unknownSymbols) {
    return new AccountSnapshotService(
        props,
        env.getProperty("trader.ibkr.account"),
        gateways.require(Broker.IBKR),
        accounts,
        market,
        instruments,
        bars,
        pool,
        days,
        snapshots,
        holdingSync,
        unknownSymbols,
        Clock.systemUTC(),
        ZoneId.of(marketData.zone()));
  }

  /**
   * Real-time account (3.0.2): subscribed on demand, wired on both dev and production instances
   * (subscriptions are counted per client and do not interfere, measured 2026-09-19).
   */
  @Bean(destroyMethod = "close")
  public LiveAccountService liveAccountService(
      AccountProperties props, Environment env, GatewayRegistry gateways, LiveAccountGateway live) {
    return new LiveAccountService(
        props,
        env.getProperty("trader.ibkr.account"),
        gateways.require(Broker.IBKR),
        live,
        Clock.systemUTC());
  }

  /** Holding risk panel (3.5.0, ARCHITECTURE §29): read-only, database only. */
  @Bean
  public HoldingReviewService holdingReviewService(
      HoldingReviewProperties props,
      AccountSnapshotRepository snapshots,
      PoolRepository pool,
      InstrumentRepository instruments,
      DailyBarRepository bars,
      RehabFactorRepository rehabs,
      TradingDayRepository days,
      SelectionEarningsRepository earnings,
      MarketDataProperties marketData) {
    return new HoldingReviewService(
        props,
        snapshots,
        pool,
        instruments,
        bars,
        rehabs,
        days,
        earnings,
        Clock.systemUTC(),
        ZoneId.of(marketData.zone()));
  }

  @Bean
  public AccountAuditService accountAuditService(
      AccountSnapshotRepository snapshots,
      TradingDayRepository days,
      JobRunRepository jobRuns,
      HoldingReviewService review,
      MarketDataProperties marketData) {
    return new AccountAuditService(
        snapshots, days, jobRuns, review, Clock.systemUTC(), ZoneId.of(marketData.zone()));
  }

  @Bean
  public AccountFacade accountFacade(
      JobService jobs,
      AccountSnapshotService service,
      AccountSnapshotRepository snapshots,
      TradingDayRepository days,
      MarketDataProperties marketData,
      Environment env) {
    return new AccountFacade(
        jobs,
        service,
        snapshots,
        days,
        env.getProperty("trader.environment"),
        Clock.systemUTC(),
        ZoneId.of(marketData.zone()));
  }

  @Bean
  @ConditionalOnExpression(
      "${trader.marketdata.schedule-enabled:false} and ${trader.account.enabled:true}")
  public AccountScheduler accountScheduler(
      AccountFacade facade,
      AccountSnapshotRepository snapshots,
      TradingDayRepository days,
      JobRunRepository jobRuns,
      MarketDataProperties marketData) {
    return new AccountScheduler(
        facade, snapshots, days, jobRuns, Clock.systemUTC(), ZoneId.of(marketData.zone()));
  }

  /**
   * Syncs holdings once after IBKR connects. If the gateway is already connected when this bean is
   * wired (auto-connect finished first), schedule one right away.
   */
  @Bean(destroyMethod = "close")
  @ConditionalOnExpression(
      "${trader.marketdata.schedule-enabled:false} and ${trader.account.enabled:true}")
  public HoldingSyncOnConnect holdingSyncOnConnect(
      HoldingSyncService sync, GatewayRegistry gateways) {
    HoldingSyncOnConnect listener = new HoldingSyncOnConnect(sync);
    BrokerGateway ibkr = gateways.require(Broker.IBKR);
    ibkr.addListener(listener);
    if (ibkr.status().state() == GatewayState.CONNECTED) {
      listener.onConnected(Broker.IBKR, false);
    }
    return listener;
  }
}
