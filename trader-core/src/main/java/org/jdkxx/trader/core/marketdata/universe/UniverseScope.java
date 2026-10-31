package org.jdkxx.trader.core.marketdata.universe;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jdkxx.trader.domain.IndexCode;
import org.jdkxx.trader.domain.PoolRole;
import org.jdkxx.trader.storage.marketdata.ConstituentRow;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.PoolRepository;
import org.jdkxx.trader.storage.marketdata.PoolRow;

/**
 * Instrument sets for each scope: universe (union of current constituents), pool, holdings,
 * benchmarks. Only instruments Futu knows (RESOLVED) and that are not delisted are returned. {@link
 * #poolAndHoldings()} includes benchmarks (the collection side treats them all alike); the
 * selection side uses {@link #candidates()} to exclude benchmarks.
 *
 * <p>Two flavours of the constituent set exist on purpose: {@link #universe()} / {@link
 * #targetBreakdown()} are the list as of now (what to collect tonight), {@link #universeOn} /
 * {@link #targetBreakdown(java.time.LocalDate)} the list valid on a given date (what an audit of
 * that date must use as its denominator).
 */
public class UniverseScope {

  private final IndexConstituentRepository constituents;
  private final PoolRepository pool;
  private final InstrumentRepository instruments;

  public UniverseScope(
      IndexConstituentRepository constituents,
      PoolRepository pool,
      InstrumentRepository instruments) {
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

  /**
   * Union of the constituents valid on a given day (for audits that check against that day's list;
   * {@link #universe()} is the current list, and the two differ after a weekend sync changed the
   * constituents). Usability (RESOLVED, not delisted) is judged by the current state, since no
   * history of it is kept.
   */
  public List<InstrumentRow> universeOn(java.time.LocalDate d) {
    Set<Long> ids = new LinkedHashSet<>();
    for (ConstituentRow r : constituents.validOn(d)) {
      ids.add(r.instrumentId());
    }
    return usable(instruments.findByIds(ids));
  }

  public List<InstrumentRow> poolAndHoldings() {
    Set<Long> ids =
        pool.findAll().stream()
            .map(PoolRow::instrumentId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    return usable(instruments.findByIds(ids));
  }

  /** Benchmark instruments (collected, never selected). */
  public List<InstrumentRow> benchmarks() {
    return byRole(r -> r == PoolRole.BENCHMARK);
  }

  /** Candidate instruments: pool members minus benchmarks. The signal stage scans these. */
  public List<InstrumentRow> candidates() {
    return byRole(r -> r != PoolRole.BENCHMARK);
  }

  private List<InstrumentRow> byRole(java.util.function.Predicate<PoolRole> keep) {
    Set<Long> ids =
        pool.findAll().stream()
            .filter(p -> keep.test(p.role()))
            .map(PoolRow::instrumentId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    return usable(instruments.findByIds(ids));
  }

  /**
   * Current constituent count per index, for the daily check: {@link #universe()} is the
   * denominator, so when constituents are wrongly removed the denominator shrinks with them and the
   * completeness check stays green; the absolute count has to be watched separately. Every index
   * appears in the map, with 0 when it has no members.
   */
  public Map<IndexCode, Integer> constituentCounts() {
    Map<IndexCode, Integer> out = new java.util.EnumMap<>(IndexCode.class);
    for (IndexCode i : IndexCode.values()) {
      out.put(i, 0);
    }
    for (ConstituentRow r : constituents.currentAll()) {
      out.merge(r.index(), 1, Integer::sum);
    }
    return out;
  }

  /**
   * Composition of the collection targets (current constituents ∪ pool ∪ holdings): how many
   * nominally, how many {@link #usable} filtered out, and how many should finally be collected.
   *
   * @param constituents distinct count of the constituents
   * @param poolExtras pool and holdings members that are <b>not</b> constituents (benchmarks,
   *     cash-management instruments and the like)
   * @param missingRows number of ids with no instrument row (the foreign key exists, so normally
   *     always 0; non-zero means the arithmetic does not add up and must be printed)
   * @param unusable the rows filtered out (not RESOLVED, or delisted), for naming each one
   * @param expected the rows that should finally be collected
   */
  public record TargetBreakdown(
      int constituents,
      int poolExtras,
      int missingRows,
      List<InstrumentRow> unusable,
      List<InstrumentRow> expected) {

    /** Nominal target count: constituents plus the pool and holdings extras. */
    public int nominal() {
      return constituents + poolExtras;
    }
  }

  /**
   * Works out the composition of the {@code completeness} denominator in one go.
   *
   * <p><b>Why this exists</b>: a silently shrinking denominator has bitten this project three
   * times: constituents wrongly removed (pure set difference), instruments marked UNRESOLVED
   * (batch-level recovery writes that itself, see {@link UnknownSymbolGuard}), a trading halt on
   * the day. Each time <b>numerator and denominator dropped by one together and the completeness
   * check stayed green</b>, and nobody saw anything. The first two each got a companion check;
   * rather than adding a third, the denominator's arithmetic itself is always kept in plain sight:
   * the audit prints nominal, each subtraction, expected and collected <b>whether green or not</b>,
   * so shrinkage cannot hide.
   */
  public TargetBreakdown targetBreakdown() {
    return breakdown(constituentIds());
  }

  /**
   * Composition of the collection targets for a given day: constituents valid on that day ({@code
   * validOn}); pool and holdings have no history, so the current ones are used. The daily bar audit
   * checks the bars of day d, so the denominator must be d's list: instruments added by a weekend
   * sync were simply not targets on the previous trading day (production false alarm on
   * 2026-10-12).
   */
  public TargetBreakdown targetBreakdown(java.time.LocalDate d) {
    Set<Long> ids = new LinkedHashSet<>();
    for (ConstituentRow r : constituents.validOn(d)) {
      ids.add(r.instrumentId());
    }
    return breakdown(ids);
  }

  private TargetBreakdown breakdown(Set<Long> constituentIds) {
    Set<Long> all = new LinkedHashSet<>(constituentIds);
    pool.findAll().forEach(p -> all.add(p.instrumentId()));
    List<InstrumentRow> rows = instruments.findByIds(all);
    List<InstrumentRow> expected = usable(rows);
    List<InstrumentRow> unusable = rows.stream().filter(r -> !isUsable(r)).toList();
    return new TargetBreakdown(
        constituentIds.size(),
        all.size() - constituentIds.size(),
        all.size() - rows.size(),
        unusable,
        expected);
  }

  private Set<Long> constituentIds() {
    Set<Long> ids = new LinkedHashSet<>();
    for (ConstituentRow r : constituents.currentAll()) {
      ids.add(r.instrumentId());
    }
    return ids;
  }

  public Map<Long, PoolRole> roles() {
    return pool.findAll().stream().collect(Collectors.toMap(PoolRow::instrumentId, PoolRow::role));
  }

  private static List<InstrumentRow> usable(List<InstrumentRow> rows) {
    return rows.stream().filter(UniverseScope::isUsable).toList();
  }

  private static boolean isUsable(InstrumentRow r) {
    return "RESOLVED".equals(r.resolveStatus()) && !r.delisted();
  }
}
