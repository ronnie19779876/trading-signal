package org.jdkxx.trader.core.selection;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;

/**
 * Quant selection universe: common stocks among index constituents, pool and holdings (§25.3 rule
 * 1). ETFs and REITs (Futu classifies them as Trust, stored as ETF) get no inputs; the remaining
 * conditions (sector, price, enough quarterly reports) are decided per day at scoring time.
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
    return byId.values().stream()
        .filter(r -> r.type() == SecurityType.STOCK)
        .sorted(Comparator.comparing(InstrumentRow::symbol))
        .toList();
  }

  /**
   * Common stocks in the pool and holdings: their announcement dates are refreshed daily (3.5.0,
   * the holdings risk panel needs scheduled dates at most one day stale); all other names weekly.
   */
  public List<InstrumentRow> poolAndHoldings() {
    return scope.poolAndHoldings().stream()
        .filter(r -> r.type() == SecurityType.STOCK)
        .sorted(Comparator.comparing(InstrumentRow::symbol))
        .toList();
  }

  /**
   * Only the given symbols (case-insensitive); symbols outside the universe are an error, so nobody
   * believes data was fetched when it was not.
   */
  public List<InstrumentRow> only(Collection<String> symbols) {
    if (symbols == null || symbols.isEmpty()) {
      return all();
    }
    Set<String> want =
        symbols.stream()
            .map(s -> s.trim().toUpperCase(Locale.ROOT))
            .filter(s -> !s.isEmpty())
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
