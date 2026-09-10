package org.jdkxx.trader.core.marketdata.universe;

import org.jdkxx.trader.domain.PoolRole;
import org.jdkxx.trader.storage.marketdata.ConstituentRow;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.PoolRepository;
import org.jdkxx.trader.storage.marketdata.PoolRow;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 各类范围的标的集合：全量（现任成分并集）、池、持仓、基准。只取富途认识（RESOLVED）且未退市的。
 * {@link #poolAndHoldings()} 含基准——采集侧一视同仁；选股侧用 {@link #candidates()} 把基准排除。
 */
public class UniverseScope {

    private final IndexConstituentRepository constituents;
    private final PoolRepository pool;
    private final InstrumentRepository instruments;

    public UniverseScope(IndexConstituentRepository constituents, PoolRepository pool, InstrumentRepository instruments) {
        this.constituents = constituents;
        this.pool = pool;
        this.instruments = instruments;
    }

    public List<InstrumentRow> universe() {
        Set<Long> ids = new LinkedHashSet<>();
        for (ConstituentRow r : constituents.currentAll()) {
            ids.add(r.instrumentId());
        }
        return usable(instruments.findByIds(ids));
    }

    public List<InstrumentRow> poolAndHoldings() {
        Set<Long> ids = pool.findAll().stream().map(PoolRow::instrumentId).collect(Collectors.toCollection(LinkedHashSet::new));
        return usable(instruments.findByIds(ids));
    }

    /** 基准标的（只采集、不选股）。 */
    public List<InstrumentRow> benchmarks() {
        return byRole(r -> r == PoolRole.BENCHMARK);
    }

    /** 候选标的：池成员减去基准。信号阶段扫描用这个。 */
    public List<InstrumentRow> candidates() {
        return byRole(r -> r != PoolRole.BENCHMARK);
    }

    private List<InstrumentRow> byRole(java.util.function.Predicate<PoolRole> keep) {
        Set<Long> ids = pool.findAll().stream().filter(p -> keep.test(p.role()))
                .map(PoolRow::instrumentId).collect(Collectors.toCollection(LinkedHashSet::new));
        return usable(instruments.findByIds(ids));
    }

    public Map<Long, PoolRole> roles() {
        return pool.findAll().stream().collect(Collectors.toMap(PoolRow::instrumentId, PoolRow::role));
    }

    private static List<InstrumentRow> usable(List<InstrumentRow> rows) {
        return rows.stream().filter(r -> "RESOLVED".equals(r.resolveStatus()) && !r.delisted()).toList();
    }
}
