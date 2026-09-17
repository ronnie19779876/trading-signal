package org.jdkxx.trader.core.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdkxx.trader.core.marketdata.InstrumentDirectory;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.bars.SettledCutoff;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.signal.EntrySignalRepository;
import org.jdkxx.trader.storage.signal.SignalEvaluationRepository;
import org.jdkxx.trader.storage.signal.SignalStore;
import org.jdkxx.trader.storage.signal.SignalTrackRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** 入场信号（第 4 期）的装配。整块只在存储启用时存在；定时评估只在开了跑批的实例装配。 */
@Configuration
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
@EnableConfigurationProperties(SignalProperties.class)
public class SignalConfiguration {

    @Bean
    public SentinelService sentinelService(InstrumentDirectory directory, DailyBarRepository bars, RehabFactorRepository rehabs,
                                           TradingDayRepository days, SettledCutoff cutoff) {
        return new SentinelService(directory, bars, rehabs, days, cutoff);
    }

    @Bean
    public org.jdkxx.trader.core.signal.ai.SignalPayloadBuilder signalPayloadBuilder(
            org.jdkxx.trader.storage.marketdata.InstrumentRepository instruments,
            org.jdkxx.trader.storage.marketdata.IndexConstituentRepository constituents, DailyBarRepository bars,
            RehabFactorRepository rehabs, TradingDayRepository days, org.jdkxx.trader.storage.marketdata.ValuationRepository valuations,
            org.jdkxx.trader.storage.marketdata.FinancialRepository financials,
            org.jdkxx.trader.storage.marketdata.CompanyProfileRepository profiles, MarketDataProperties marketData) {
        return new org.jdkxx.trader.core.signal.ai.SignalPayloadBuilder(instruments, constituents, bars, rehabs, days, valuations,
                financials, profiles, ZoneId.of(marketData.zone()));
    }

    @Bean
    public SignalLedgerService signalLedgerService(EntrySignalRepository signals, SignalTrackRepository tracks,
                                                   InstrumentDirectory directory, DailyBarRepository bars,
                                                   RehabFactorRepository rehabs, TradingDayRepository days) {
        return new SignalLedgerService(signals, tracks, directory, bars, rehabs, days);
    }

    @Bean
    public SignalEvaluationService signalEvaluationService(UniverseScope scope, DailyBarRepository bars, RehabFactorRepository rehabs,
                                                           TradingDayRepository days, SignalEvaluationRepository evaluations,
                                                           EntrySignalRepository signals, SignalStore store,
                                                           SignalLedgerService ledger, SettledCutoff cutoff, ObjectMapper json,
                                                           org.jdkxx.trader.core.signal.ai.AiVetoService ai) {
        return new SignalEvaluationService(scope, bars, rehabs, days, evaluations, signals, store, ledger, cutoff, json, ai);
    }

    @Bean
    public org.jdkxx.trader.core.signal.ai.AiVetoService aiVetoService(org.jdkxx.trader.core.signal.ai.SignalPayloadBuilder payloads,
                                                                       org.jdkxx.trader.ai.veto.VetoClient client,
                                                                       org.jdkxx.trader.ai.AiProperties props,
                                                                       org.jdkxx.trader.storage.signal.AiAnalysisRepository analyses,
                                                                       ObjectMapper json, MarketDataProperties marketData) {
        return new org.jdkxx.trader.core.signal.ai.AiVetoService(payloads, client, props, analyses, json, Clock.systemUTC(),
                ZoneId.of(marketData.zone()));
    }

    @Bean
    public org.jdkxx.trader.core.signal.ai.AiAnalysisFacade aiAnalysisFacade(org.jdkxx.trader.core.signal.ai.AiVetoService ai,
                                                                             SentinelService sentinel, InstrumentDirectory directory,
                                                                             org.jdkxx.trader.storage.signal.AiAnalysisRepository analyses,
                                                                             MarketDataProperties marketData) {
        return new org.jdkxx.trader.core.signal.ai.AiAnalysisFacade(ai, sentinel, directory, analyses, Clock.systemUTC(),
                ZoneId.of(marketData.zone()));
    }

    @Bean
    public SignalFacade signalFacade(JobService jobs, SignalEvaluationService evaluation, SentinelService sentinel,
                                     SignalEvaluationRepository evaluations, EntrySignalRepository signals,
                                     SignalTrackRepository tracks, InstrumentDirectory directory, TradingDayRepository days,
                                     SettledCutoff cutoff) {
        return new SignalFacade(jobs, evaluation, sentinel, evaluations, signals, tracks, directory, days, cutoff);
    }

    @Bean
    public SignalAuditService signalAuditService(SignalEvaluationService evaluation, SignalEvaluationRepository evaluations,
                                                 EntrySignalRepository signals, SignalTrackRepository tracks,
                                                 TradingDayRepository days, JobRunRepository jobRuns, MarketDataProperties marketData,
                                                 org.jdkxx.trader.storage.signal.AiAnalysisRepository analyses) {
        return new SignalAuditService(evaluation, evaluations, signals, tracks, days, jobRuns, analyses, Clock.systemUTC(),
                ZoneId.of(marketData.zone()));
    }

    @Bean
    @ConditionalOnExpression("${trader.marketdata.schedule-enabled:false} and ${trader.signal.enabled:true}")
    public SignalScheduler signalScheduler(SignalFacade facade, SignalEvaluationRepository evaluations, TradingDayRepository days,
                                           JobRunRepository jobRuns, MarketDataProperties marketData) {
        return new SignalScheduler(facade, evaluations, days, jobRuns, Clock.systemUTC(), ZoneId.of(marketData.zone()));
    }
}
