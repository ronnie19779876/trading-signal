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

/** 各类范围的标的集合：全量（现任成分并集）、池、持仓。只取富途认识（RESOLVED）且未退市的。 */
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

    public Map<Long, PoolRole> roles() {
        return pool.findAll().stream().collect(Collectors.toMap(PoolRow::instrumentId, PoolRow::role));
    }

    private static List<InstrumentRow> usable(List<InstrumentRow> rows) {
        return rows.stream().filter(r -> "RESOLVED".equals(r.resolveStatus()) && !r.delisted()).toList();
    }
}
