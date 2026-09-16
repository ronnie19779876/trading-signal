package org.jdkxx.trader.core.signal;

import org.jdkxx.trader.core.marketdata.InstrumentDirectory;
import org.jdkxx.trader.core.marketdata.bars.SettledCutoff;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 入场信号（第 4 期）的装配。整块只在存储启用时存在。 */
@Configuration
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SignalConfiguration {

    @Bean
    public SentinelService sentinelService(InstrumentDirectory directory, DailyBarRepository bars, RehabFactorRepository rehabs,
                                           TradingDayRepository days, SettledCutoff cutoff) {
        return new SentinelService(directory, bars, rehabs, days, cutoff);
    }
}
