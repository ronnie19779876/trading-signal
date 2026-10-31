package org.jdkxx.trader.core.marketdata.universe;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentStatic;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.UnknownSymbolException;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Batch-level recovery: when Futu rejects a whole batch as "unknown stock", find the bad symbols,
 * mark them UNRESOLVED and let the caller drop them and retry once, so one bad symbol only costs
 * itself instead of the whole batch.
 *
 * <p><b>Why Futu stops knowing a symbol that was already resolved</b>: symbols get renamed by
 * corporate actions. On 2026-10-06 Paramount Skydance's USD 110 billion acquisition of Warner Bros.
 * Discovery closed; PSKY was renamed Skydance, the symbol became SKYD, the class B listing moved
 * Nasdaq → NYSE, and Futu immediately stopped recognizing PSKY. {@link
 * UniverseSyncService#resolveStatics} <b>only looks at non-RESOLVED instruments</b>, so PSKY,
 * resolved successfully on 2026-09-03, was exempt from checking forever and kept entering
 * collection batches as RESOLVED. It poisoned the increment's 90-instrument batch and the
 * valuation's 400-instrument batch once each (losing 90 bars and 400 valuations).
 *
 * <h2>Two-layer decision: never decide life or death by text</h2>
 *
 * <ol>
 *   <li>The <b>trigger</b> only looks at the exception type: an <b>explicit rejection</b> by the
 *       broker ({@link RequestRejectedException}). Wording is ignored: one copy change at Futu must
 *       not disable recovery. Timeouts and disconnects never trigger it (see guard one).
 *   <li>The <b>criterion</b> only accepts a structured fact: call {@code staticInfo} on the whole
 *       batch and take {@code brokerId==0}. It is the same criterion {@code resolveStatics} already
 *       uses, so the rules do not diverge.
 * </ol>
 *
 * {@link UnknownSymbolException#named()} is only used for cross-checking and logging and <b>takes
 * no part in dropping</b>: measured 2026-10-07, the retMsg of {@code snapshots([PSKY, NOSUCHXYZ,
 * AAPL])} only mentioned PSKY and not the other bad symbol, so dropping by text would miss it and
 * the next batch would still be poisoned.
 *
 * <p>The criterion works because {@code staticInfo} is <b>immune in a mixed batch</b>: measured
 * against the real gateway on 2026-10-07, {@code staticInfo([PSKY, AAPL, MSFT])} succeeded with 3
 * rows, the PSKY row being {@code name=unknown stock, brokerId=0, delisted=true} and AAPL/MSFT
 * normal; in the same run {@code snapshots([PSKY, AAPL, MSFT])} still failed as a whole while
 * {@code snapshots([AAPL, MSFT])} succeeded. Only the three controls together prove this is an API
 * difference and not "Futu recognizes PSKY again".
 *
 * <h2>Three guards</h2>
 *
 * <ol>
 *   <li><b>Only explicit rejections count.</b> Timeout / disconnect / cancellation do not trigger
 *       it. Otherwise one Futu entitlement tier flip (market-data entitlement flips between the
 *       mainland and international tier depending on the login IP, measured) would mark hundreds of
 *       instruments UNRESOLVED at once.
 *   <li><b>Too many bad symbols in one batch leaves the whole batch alone</b> ({@code
 *       max-unknown-per-batch}, default 3). Basis: in more than a month live with 500+ instruments,
 *       "RESOLVED → unknown to Futu" happened <b>once</b>. Newly added constituents go through
 *       PENDING, not this path. Four or more in one batch looks more like a problem on Futu's side,
 *       and a collection job must not silently downgrade hundreds of instruments. Same philosophy
 *       as the constituent sync guard: <b>never downgrade in bulk silently</b>; being blocked once
 *       is exactly when someone should look.
 *   <li><b>A downgrade must be visible</b>. This one is not in this class but in the audit's {@code
 *       resolveDowngrade} check. Marking UNRESOLVED removes the instrument from every denominator
 *       built on {@link UniverseScope#usable}, so the completeness check's denominator shrinks and
 *       <b>the check stays green</b> (the same pitfall as "the constituent set difference is
 *       invisible"). Hence a check that reports the non-RESOLVED instruments by <b>absolute
 *       count</b>.
 * </ol>
 *
 * <p>The holdings side passes {@code mayMark=false}: a rename of something we actually hold affects
 * pricing and real-time subscriptions and deserves a human look, so it is only dropped from this
 * batch and nothing is written (the job is recorded PARTIAL).
 *
 * <p>Thread note: {@link #inspect} blocks on {@code staticInfo} (30 s per chunk), so it must only
 * be called from a job or worker thread, never from a gateway callback thread.
 */
public class UnknownSymbolGuard {

  private static final Logger log = LoggerFactory.getLogger(UnknownSymbolGuard.class);

  /**
   * @param exclude symbols verified as "unknown to Futu" that the caller should drop before
   *     retrying; empty means the guard declined to intervene and the caller proceeds as before
   * @param marked symbols this call actually wrote to the database (marked UNRESOLVED)
   * @param detail explanation for the log and {@code ctx.partial}; never null
   */
  public record Outcome(Set<String> exclude, Set<String> marked, String detail) {

    public Outcome {
      exclude = Set.copyOf(exclude);
      marked = Set.copyOf(marked);
    }

    public boolean canRetry() {
      return !exclude.isEmpty();
    }
  }

  private final MarketDataGateway gateway;
  private final InstrumentRepository instruments;
  private final int staticBatchSize;
  private final int maxUnknownPerBatch;

  public UnknownSymbolGuard(
      MarketDataGateway gateway,
      InstrumentRepository instruments,
      int staticBatchSize,
      int maxUnknownPerBatch) {
    this.gateway = gateway;
    this.instruments = instruments;
    this.staticBatchSize = Math.max(1, staticBatchSize);
    this.maxUnknownPerBatch = Math.max(0, maxUnknownPerBatch);
  }

  /**
   * Asked once after a batch call failed: are there bad symbols, and which ones should be dropped.
   *
   * @param batch the whole batch. <b>Intervenes even for a single instrument</b>: unsubscribing a
   *     symbol Futu no longer knows fails forever, and removing it from the local book is exactly
   *     the fix, so "only one, skip the check" must not be a shortcut
   * @param failure the exception thrown by the batch call (may be wrapped, e.g. {@code
   *     ExecutionException})
   * @param mayMark whether writing to the database is allowed. The holdings and real-time
   *     subscription sides pass false
   * @return empty when the guard does not intervene; the caller then proceeds as before
   */
  public Optional<Outcome> inspect(List<Instrument> batch, Throwable failure, boolean mayMark) {
    // Guard one: only an explicit broker rejection counts
    RequestRejectedException rejected = rejection(failure);
    if (rejected == null || batch == null || batch.isEmpty()) {
      return Optional.empty();
    }
    Set<String> named = rejected instanceof UnknownSymbolException u ? u.named() : Set.of();

    Set<String> unknown;
    try {
      unknown = findUnknown(batch);
    } catch (Exception e) {
      // If the verification itself fails, do nothing: no criterion, no database write
      log.warn("核实未知代码失败，整批按原有方式处理：{}", e.toString());
      return Optional.of(new Outcome(Set.of(), Set.of(), "核实未知代码失败：" + e));
    }

    if (unknown.isEmpty()) {
      String detail =
          named.isEmpty() ? "富途拒绝了整批，但逐只核实都认识——不是未知代码问题" : "富途点名 " + named + "，但逐只核实都认识——不按未知代码处理";
      log.warn("{}（{} 只）：{}", "批次被拒", batch.size(), detail);
      return Optional.of(new Outcome(Set.of(), Set.of(), detail));
    }

    // Guard two: too many bad symbols leaves the whole batch alone
    if (unknown.size() > maxUnknownPerBatch) {
      String detail =
          "一批 "
              + batch.size()
              + " 只里有 "
              + unknown.size()
              + " 只富途不认识，超过阈值 "
              + maxUnknownPerBatch
              + "，整批不动也不标记（更像富途侧出了事）："
              + head(unknown);
      log.warn(detail);
      return Optional.of(new Outcome(Set.of(), Set.of(), detail));
    }

    Set<String> marked = new LinkedHashSet<>();
    if (mayMark) {
      // Look rows up with the batch's original Instrument rather than rebuilding one from the
      // symbol: the market comes with it
      Map<String, Instrument> bySymbol = new LinkedHashMap<>();
      batch.forEach(i -> bySymbol.putIfAbsent(i.symbol().toUpperCase(Locale.ROOT), i));
      for (String symbol : unknown) {
        Instrument instrument = bySymbol.get(symbol);
        if (instrument == null) {
          continue;
        }
        Optional<InstrumentRow> row = instruments.find(instrument);
        if (row.isPresent() && !"UNRESOLVED".equals(row.get().resolveStatus())) {
          instruments.markUnresolved(row.get().id());
          marked.add(symbol);
        }
      }
    }

    String detail =
        "富途不认识 "
            + unknown
            + (named.isEmpty() ? "" : "（它只点名了 " + named + "）")
            + "；已"
            + (mayMark ? "标成 UNRESOLVED " + marked : "剔除但不写库（持仓侧）")
            + "，剔除后重试本批";
    log.warn(detail);
    return Optional.of(new Outcome(unknown, marked, detail));
  }

  /**
   * Fetches static info for the whole batch and collects the symbols Futu does not know: those with
   * brokerId==0, and those requested but not answered. Symbols are compared upper-cased.
   */
  private Set<String> findUnknown(List<Instrument> batch) throws Exception {
    Set<String> unknown = new LinkedHashSet<>();
    for (int from = 0; from < batch.size(); from += staticBatchSize) {
      List<Instrument> chunk = batch.subList(from, Math.min(from + staticBatchSize, batch.size()));
      List<InstrumentStatic> statics = gateway.staticInfo(chunk).get(30, TimeUnit.SECONDS);
      Set<String> answered = new LinkedHashSet<>();
      for (InstrumentStatic s : statics) {
        String symbol = s.instrument().symbol().toUpperCase(Locale.ROOT);
        answered.add(symbol);
        // Measured: Futu returns a row even for a symbol it does not know (name "unknown stock",
        // brokerId=0, delisted=true)
        if (s.brokerId() == 0) {
          unknown.add(symbol);
        }
      }
      for (Instrument i : chunk) {
        String symbol = i.symbol().toUpperCase(Locale.ROOT);
        if (!answered.contains(symbol)) {
          unknown.add(symbol);
        }
      }
    }
    return unknown;
  }

  /**
   * Walks the cause chain to the gateway rejection: every caller uses {@code future.get()}, so the
   * exception arrives wrapped in ExecutionException.
   */
  private static RequestRejectedException rejection(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
      if (c instanceof RequestRejectedException r) {
        return r;
      }
    }
    return null;
  }

  private static String head(Set<String> s) {
    List<String> l = List.copyOf(s);
    return l.size() <= 12 ? l.toString() : l.subList(0, 12) + "…";
  }
}
