package org.jdkxx.trader.core.selection;

import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 量化选股的标的范围：成分股 ∪ 池 ∪ 持仓里的普通股（§25.3 第 1 条）。
 * ETF 与 REITs（富途归为 Trust、库里是 ETF）不取输入；其余条件（板块、价格、季报够不够）在打分时逐日判定。
 */
public class SelectionTargets {

    private final UniverseScope scope;

    public SelectionTargets(UniverseScope scope) {
        this.scope = scope;
    }

    public List<InstrumentRow> all() {
        Map<Long, InstrumentRow> byId = new LinkedHashMap<>();
        scope.universe().forEach(r -> byId.put(r.id(), r));
        scope.poolAndHoldings().forEach(r -> byId.put(r.id(), r));
        return byId.values().stream().filter(r -> r.type() == SecurityType.STOCK)
                .sorted(Comparator.comparing(InstrumentRow::symbol)).toList();
    }

    /** 只取指定代码（大小写不敏感）；给了不在范围内的代码直接报错，免得以为取了其实没取。 */
    public List<InstrumentRow> only(Collection<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return all();
        }
        Set<String> want = symbols.stream().map(s -> s.trim().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        List<InstrumentRow> hit = all().stream().filter(r -> want.contains(r.symbol())).toList();
        Set<String> found = hit.stream().map(InstrumentRow::symbol).collect(Collectors.toSet());
        List<String> missing = want.stream().filter(s -> !found.contains(s)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("不在量化选股范围内（须是成分股或池内的普通股）：" + String.join(",", missing));
        }
        return hit;
    }
}
