package org.jdkxx.trader.core.marketdata.bars;

import org.jdkxx.trader.core.marketdata.InstrumentDirectory;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.domain.Adjustment;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;

import java.time.LocalDate;
import java.util.List;

/** K 线查询 + 读取层复权。 */
public class BarQueryService {

    private final InstrumentDirectory directory;
    private final DailyBarRepository bars;
    private final RehabFactorRepository rehabs;
    private final FactorMode mode;

    public BarQueryService(InstrumentDirectory directory, DailyBarRepository bars, RehabFactorRepository rehabs, MarketDataProperties props) {
        this.directory = directory;
        this.bars = bars;
        this.rehabs = rehabs;
        this.mode = props.adjust().factorMode();
    }

    public List<DailyBar> bars(String symbol, LocalDate from, LocalDate to, Adjustment adjustment) {
        InstrumentRow row = directory.require(symbol);
        List<DailyBar> raw = bars.find(row.instrument(), row.id(), from, to);
        if (adjustment == null || adjustment == Adjustment.NONE) {
            return raw;
        }
        List<RehabFactor> factors = rehabs.find(row.instrument(), row.id());
        return BarAdjuster.adjust(raw, factors, adjustment, mode);
    }

    public List<RehabFactor> rehab(String symbol) {
        InstrumentRow row = directory.require(symbol);
        return rehabs.find(row.instrument(), row.id());
    }
}
