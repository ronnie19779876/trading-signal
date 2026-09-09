package org.jdkxx.trader.core.marketdata.fundamentals;

import org.jdkxx.trader.core.marketdata.InstrumentDirectory;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.CompanyProfile;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.ValuationSnapshot;
import org.jdkxx.trader.storage.marketdata.CompanyProfileRepository;
import org.jdkxx.trader.storage.marketdata.FinancialRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.ValuationRepository;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 基本面查询。 */
public class FundamentalsQueryService {

    /** 一只标的的基本面概览。 */
    public record Overview(String symbol, String name, ValuationSnapshot valuation,
                           List<FinancialReport> mainIndex, Map<String, String> profile) {
    }

    /** 覆盖情况。 */
    public record Coverage(LocalDate latestDate, long targets, long withValuationOnDate, long reports,
                           long poolWithReports, long poolSize) {
    }

    private final InstrumentDirectory directory;
    private final ValuationRepository valuations;
    private final FinancialRepository financials;
    private final CompanyProfileRepository profiles;
    private final UniverseScope scope;
    private final MarketDataProperties props;

    public FundamentalsQueryService(InstrumentDirectory directory, ValuationRepository valuations,
                                    FinancialRepository financials, CompanyProfileRepository profiles,
                                    UniverseScope scope, MarketDataProperties props) {
        this.directory = directory;
        this.valuations = valuations;
        this.financials = financials;
        this.profiles = profiles;
        this.scope = scope;
        this.props = props;
    }

    public Overview overview(String symbol) {
        InstrumentRow row = directory.require(symbol);
        LocalDate latest = valuations.maxTradeDate().orElse(LocalDate.now());
        List<ValuationSnapshot> v = valuations.find(row.instrument(), row.id(), latest.minusDays(10), latest);
        List<FinancialReport> main = financials.find(row.instrument(), row.id(), FinancialStatement.MAIN_INDEX, 4);
        Optional<CompanyProfile> p = profiles.find(row.instrument(), row.id());
        return new Overview(row.symbol(), row.name(), v.isEmpty() ? null : v.getLast(), main,
                p.map(CompanyProfile::fields).orElse(Map.of()));
    }

    public List<ValuationSnapshot> valuations(String symbol, LocalDate from, LocalDate to) {
        InstrumentRow row = directory.require(symbol);
        return valuations.find(row.instrument(), row.id(), from, to);
    }

    public List<FinancialReport> reports(String symbol, FinancialStatement statement, int limit) {
        InstrumentRow row = directory.require(symbol);
        return financials.find(row.instrument(), row.id(), statement, Math.clamp(limit, 1, 50));
    }

    public Coverage coverage() {
        Map<Long, InstrumentRow> targets = new LinkedHashMap<>();
        scope.universe().forEach(r -> targets.put(r.id(), r));
        List<InstrumentRow> pool = scope.poolAndHoldings();
        pool.forEach(r -> targets.put(r.id(), r));
        LocalDate latest = valuations.maxTradeDate().orElse(null);
        long withValuation = latest == null ? 0 : valuations.instrumentIdsOn(latest).stream()
                .filter(targets::containsKey).count();
        Map<Long, LocalDate> byInstrument = new LinkedHashMap<>();
        financials.latestPeriods().forEach(l -> byInstrument.put(l.instrumentId(), l.periodEnd()));
        long poolWithReports = pool.stream().filter(r -> byInstrument.containsKey(r.id())).count();
        return new Coverage(latest, targets.size(), withValuation, financials.countReports(), poolWithReports, pool.size());
    }

    public boolean enabled() {
        return props.fundamentals().enabled();
    }
}
