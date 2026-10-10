package org.jdkxx.trader.core.selection;

import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.gateway.PriceHistoryGateway;
import org.jdkxx.trader.gateway.ReferenceDataGateway;
import org.jdkxx.trader.gateway.SelectionDataGateway;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.FinancialRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.selection.SelectionBackfillStateRepository;
import org.jdkxx.trader.storage.selection.SelectionCoverageRepository;
import org.jdkxx.trader.storage.selection.SelectionEarningsRepository;
import org.jdkxx.trader.storage.selection.SelectionPriceBarRepository;
import org.jdkxx.trader.storage.selection.SelectionPriceSeamRepository;
import org.jdkxx.trader.storage.selection.SelectionValuationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 量化选股（第 6 期，ARCHITECTURE §25）的装配。独立功能：不依赖入场信号的任何 bean。
 * 整块只在存储启用时存在；6.1 只有手工回补与只读报告，没有定时作业。
 */
@Configuration
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
@EnableConfigurationProperties(SelectionProperties.class)
public class SelectionConfiguration {

    @Bean
    public SelectionTargets selectionTargets(UniverseScope scope) {
        return new SelectionTargets(scope);
    }

    @Bean
    public SelectionBackfillService selectionBackfillService(SelectionProperties props, SelectionTargets targets,
                                                             SelectionBackfillStateRepository states, PriceHistoryGateway prices,
                                                             ReferenceDataGateway contracts, SelectionDataGateway fundamentals,
                                                             InstrumentRepository instruments, DailyBarRepository dailyBars,
                                                             RehabFactorRepository rehabs, FinancialRepository financials,
                                                             SelectionPriceBarRepository priceBars, SelectionPriceSeamRepository seams,
                                                             SelectionValuationRepository valuations, SelectionEarningsRepository earnings,
                                                             MarketDataProperties marketData) {
        return new SelectionBackfillService(props, targets, states, prices, contracts, fundamentals, instruments, dailyBars, rehabs,
                financials, priceBars, seams, valuations, earnings, Clock.systemUTC(), ZoneId.of(marketData.zone()));
    }

    @Bean
    public SelectionInputsService selectionInputsService(SelectionProperties props, SelectionTargets targets,
                                                         SelectionBackfillStateRepository states, SelectionPriceBarRepository priceBars,
                                                         SelectionPriceSeamRepository seams, SelectionValuationRepository valuations,
                                                         SelectionEarningsRepository earnings, SelectionCoverageRepository coverage,
                                                         MarketDataProperties marketData) {
        return new SelectionInputsService(props, targets, states, priceBars, seams, valuations, earnings, coverage,
                Clock.systemUTC(), ZoneId.of(marketData.zone()));
    }

    @Bean
    public SelectionFacade selectionFacade(JobService jobs, SelectionTargets targets, SelectionBackfillService backfill,
                                           SelectionInputsService inputs, SelectionPriceSeamRepository seams) {
        return new SelectionFacade(jobs, targets, backfill, inputs, seams);
    }
}
