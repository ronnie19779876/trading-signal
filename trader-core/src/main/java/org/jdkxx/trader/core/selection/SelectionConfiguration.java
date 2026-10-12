package org.jdkxx.trader.core.selection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.bars.SettledCutoff;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.gateway.PriceHistoryGateway;
import org.jdkxx.trader.gateway.ReferenceDataGateway;
import org.jdkxx.trader.gateway.SelectionDataGateway;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.FinancialRepository;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.selection.SelectionBackfillStateRepository;
import org.jdkxx.trader.storage.selection.SelectionCoverageRepository;
import org.jdkxx.trader.storage.selection.SelectionEarningsRepository;
import org.jdkxx.trader.storage.selection.SelectionInputRepository;
import org.jdkxx.trader.storage.selection.SelectionPriceBarRepository;
import org.jdkxx.trader.storage.selection.SelectionPriceSeamRepository;
import org.jdkxx.trader.storage.selection.SelectionScoreRepository;
import org.jdkxx.trader.storage.selection.SelectionSimRepository;
import org.jdkxx.trader.storage.selection.SelectionSecurityRepository;
import org.jdkxx.trader.storage.selection.SelectionValuationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 量化选股（第 6 期，ARCHITECTURE §25）的装配。独立功能：不依赖入场信号的任何 bean。
 * 整块只在存储启用时存在；定时任务只在开了跑批（schedule-enabled）且 trader.selection.enabled 的实例装配。
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
                                                             SelectionSecurityRepository securities, MarketDataProperties marketData) {
        return new SelectionBackfillService(props, targets, states, prices, contracts, fundamentals, instruments, dailyBars, rehabs,
                financials, priceBars, seams, valuations, earnings, securities, Clock.systemUTC(), ZoneId.of(marketData.zone()));
    }

    @Bean
    public SelectionSectors selectionSectors(IndexConstituentRepository constituents) {
        return new SelectionSectors(constituents);
    }

    @Bean
    public SelectionDataLoader selectionDataLoader(SelectionTargets targets, SelectionSectors sectors, SelectionInputRepository inputs,
                                                   SelectionSecurityRepository securities, DailyBarRepository dailyBars,
                                                   RehabFactorRepository rehabs, TradingDayRepository days) {
        return new SelectionDataLoader(targets, sectors, inputs, securities, dailyBars, rehabs, days);
    }

    @Bean
    public SelectionScoringService selectionScoringService(SelectionDataLoader loader, SelectionScoreRepository scores,
                                                           TradingDayRepository days, SettledCutoff cutoff, ObjectMapper json) {
        return new SelectionScoringService(loader, scores, days, cutoff::current, json);
    }

    @Bean
    public SelectionSimService selectionSimService(SelectionSimRepository sim, SelectionScoreRepository scores, SelectionTargets targets,
                                                   InstrumentRepository instruments, DailyBarRepository dailyBars, RehabFactorRepository rehabs,
                                                   TradingDayRepository days, SettledCutoff cutoff, ObjectMapper json,
                                                   MarketDataProperties marketData) {
        return new SelectionSimService(sim, scores, targets, instruments, dailyBars, rehabs, days, cutoff::current, json,
                Clock.systemUTC(), ZoneId.of(marketData.zone()));
    }

    @Bean
    public SelectionDailyService selectionDailyService(SelectionTargets targets, SelectionValuationRepository valuations,
                                                       SelectionEarningsRepository earnings, FinancialRepository financials,
                                                       SelectionDataGateway fundamentals, SelectionScoringService scoring,
                                                       SelectionBackfillService backfill, SelectionSimService sim) {
        return new SelectionDailyService(targets, valuations, earnings, financials, fundamentals, scoring, backfill, sim);
    }

    @Bean
    public SelectionAuditService selectionAuditService(SelectionTargets targets, SelectionScoreRepository scores,
                                                       SelectionValuationRepository valuations, SelectionPriceSeamRepository seams,
                                                       SelectionSimService sim, SettledCutoff cutoff) {
        return new SelectionAuditService(targets, scores, valuations, seams, sim, cutoff::current, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnExpression("${trader.marketdata.schedule-enabled:false} and ${trader.selection.enabled:true}")
    public SelectionScheduler selectionScheduler(SelectionFacade facade, SelectionScoreRepository scores, SelectionSimService sim,
                                                 TradingDayRepository days, JobRunRepository jobRuns, MarketDataProperties marketData) {
        return new SelectionScheduler(facade, scores, sim, days, jobRuns, Clock.systemUTC(), ZoneId.of(marketData.zone()));
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
                                           SelectionInputsService inputs, SelectionPriceSeamRepository seams,
                                           SelectionScoringService scoring, SelectionDailyService daily, SelectionAuditService audit,
                                           SelectionScoreRepository scores, SelectionSimService sim) {
        return new SelectionFacade(jobs, targets, backfill, inputs, seams, scoring, daily, audit, scores, sim);
    }
}
