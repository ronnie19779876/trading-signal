package org.jdkxx.trader.core.marketdata.universe;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.domain.IndexCode;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentStatic;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.ConstituentRow;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Constituent sync: source → idempotent instrument upsert → set difference against the current
 * constituents (new members get since, leavers get until) → Futu static info resolution. When one
 * index's source fails, that index keeps its old constituents and the failure is recorded; the
 * other index is not interrupted.
 *
 * <p>since / until are today's date in the configured zone (US/Eastern). The scheduled sync runs on
 * Saturday, so a change becomes effective after Friday and audits of Friday still see Friday's
 * list.
 */
public class UniverseSyncService {

  private static final Logger log = LoggerFactory.getLogger(UniverseSyncService.class);

  public record IndexResult(
      IndexCode index, int fetched, int added, int removed, int unchanged, String error) {}

  private final MarketDataProperties props;
  private final UniverseSource source;
  private final SpyHoldingsCrossCheck spyCheck;
  private final InstrumentRepository instruments;
  private final IndexConstituentRepository constituents;
  private final MarketDataGateway gateway;
  private final ZoneId zone;

  public UniverseSyncService(
      MarketDataProperties props,
      UniverseSource source,
      SpyHoldingsCrossCheck spyCheck,
      InstrumentRepository instruments,
      IndexConstituentRepository constituents,
      MarketDataGateway gateway) {
    this.props = props;
    this.source = source;
    this.spyCheck = spyCheck;
    this.instruments = instruments;
    this.constituents = constituents;
    this.gateway = gateway;
    this.zone = ZoneId.of(props.zone());
  }

  /** Full sync (job body). {@code force=true} bypasses the exit-count guard; see {@link #apply}. */
  public String sync(JobContext ctx) {
    return sync(ctx, false);
  }

  public String sync(JobContext ctx, boolean force) {
    List<String> parts = new ArrayList<>();
    Map<IndexCode, List<ConstituentEntry>> fetched = new LinkedHashMap<>();
    for (IndexCode index : IndexCode.values()) {
      ctx.progress("下载 " + index.displayName() + " 成分股");
      try {
        fetched.put(index, source.fetch(index));
      } catch (RuntimeException e) {
        log.warn("{} 成分股下载失败：{}", index, e.toString());
        ctx.partial(index.displayName() + " 来源失败：" + e.getMessage());
      }
    }
    for (Map.Entry<IndexCode, List<ConstituentEntry>> e : fetched.entrySet()) {
      IndexResult r = apply(e.getKey(), e.getValue(), source.name(), force);
      if (r.error() != null) {
        parts.add(e.getKey() + " " + r.error());
        ctx.partial(
            e.getKey()
                + " "
                + r.error()); // the job is recorded PARTIAL and shows up in the jobs health metric
      } else {
        parts.add(
            e.getKey()
                + " 抓到 "
                + r.fetched()
                + "：新增 "
                + r.added()
                + "、退出 "
                + r.removed()
                + "、不变 "
                + r.unchanged());
      }
    }
    if (props.universe().crossCheckSpy() && fetched.containsKey(IndexCode.SP500)) {
      ctx.progress("用 SPY 持仓交叉核对标普 500");
      parts.add(crossCheck(fetched.get(IndexCode.SP500)));
    }
    ctx.progress("向富途解析静态信息");
    parts.add(resolveStatics(ctx));
    return String.join("；", parts);
  }

  /**
   * CSV import (the same set-difference logic, guarded by the same exit-count guard). Only the
   * indexes that appear in the CSV are touched.
   */
  public List<IndexResult> importCsv(String csv) {
    return importCsv(csv, false);
  }

  public List<IndexResult> importCsv(String csv, boolean force) {
    List<ConstituentEntry> entries = CsvUniverse.parse(csv);
    Map<IndexCode, List<ConstituentEntry>> byIndex = new LinkedHashMap<>();
    for (ConstituentEntry e : entries) {
      byIndex.computeIfAbsent(e.index(), k -> new ArrayList<>()).add(e);
    }
    List<IndexResult> out = new ArrayList<>();
    for (Map.Entry<IndexCode, List<ConstituentEntry>> e : byIndex.entrySet()) {
      out.add(apply(e.getKey(), e.getValue(), "CSV", force));
    }
    return out;
  }

  /**
   * Syncs one index by set difference. <b>Compute the exit set and pass the guard first, then
   * act</b>: when blocked, not a single constituent row of the index changes; there is no partial
   * application (a half-old, half-new state is harder to investigate than no sync at all). The
   * guard works on a read-only lookup of the instrument rows; they are upserted only after it
   * passes, so a blocked sync writes nothing at all. The add / close loop is not wrapped in a
   * transaction.
   *
   * <p>Why the guard is needed: {@code apply} is a pure set difference, so whatever the source
   * leaves out exits. {@code WikipediaUniverseSource} only errors when it parses fewer than 50
   * rows, while the S&amp;P 500 has 503: a page structure change that leaves it parsing 60 rows
   * still counts as "credible", and one sync would mark 440+ members as exited. Worse, it is
   * **invisible**: {@code BarAuditService}'s denominator comes from the same source, so when
   * constituents drop the denominator drops too, the completeness check stays green, and that
   * night's increment simply stops collecting those instruments (found in the 2026-09-25
   * full-project review).
   */
  IndexResult apply(
      IndexCode index, List<ConstituentEntry> entries, String sourceName, boolean force) {
    LocalDate today = LocalDate.now(zone);
    // Read-only pass for the guard: upserting every source entry first meant a blocked sync still
    // inserted instrument rows for the new symbols (found in the 2026-10-31 review). A symbol
    // without a row cannot be a current member, so find() is enough to compute the exits.
    Set<Instrument> fetched = new LinkedHashSet<>();
    Set<Long> knownIds = new HashSet<>();
    for (ConstituentEntry e : entries) {
      Instrument instrument = Instrument.us(e.symbol());
      if (fetched.add(instrument)) {
        instruments.find(instrument).ifPresent(r -> knownIds.add(r.id()));
      }
    }
    Map<Long, ConstituentRow> current = new HashMap<>();
    for (ConstituentRow r : constituents.current(index)) {
      current.put(r.instrumentId(), r);
    }
    List<Long> leaving = current.keySet().stream().filter(id -> !knownIds.contains(id)).toList();
    int limit = removalLimit(current.size());
    if (!force && leaving.size() > limit) {
      String why =
          "退出 "
              + leaving.size()
              + " 只超过阈值 "
              + limit
              + "（现有 "
              + current.size()
              + "，来源 "
              + sourceName
              + " 只给了 "
              + fetched.size()
              + "）：整个指数跳过，未做任何改动。"
              + "确认来源无误后用 force=true 放行";
      log.warn("{} 成分股同步被守护挡下：{}", index, why);
      return new IndexResult(index, fetched.size(), 0, 0, current.size(), why);
    }
    Map<Long, ConstituentEntry> wanted = new LinkedHashMap<>();
    for (ConstituentEntry e : entries) {
      long id = instruments.upsert(Instrument.us(e.symbol()), e.name());
      wanted.putIfAbsent(id, e);
    }
    int added = 0;
    int unchanged = 0;
    for (Map.Entry<Long, ConstituentEntry> w : wanted.entrySet()) {
      ConstituentEntry e = w.getValue();
      if (current.containsKey(w.getKey())) {
        constituents.updateSector(index, w.getKey(), e.sector(), e.subIndustry());
        unchanged++;
      } else {
        constituents.add(index, w.getKey(), e.sector(), e.subIndustry(), today, sourceName);
        added++;
      }
      constituents.setAddedOn(index, w.getKey(), e.addedDate());
    }
    for (Long id : leaving) {
      constituents.close(index, id, today);
    }
    int removed = leaving.size();
    log.info(
        "{} 成分股同步（{}）：抓到 {}，新增 {}，退出 {}，不变 {}",
        index,
        sourceName,
        wanted.size(),
        added,
        removed,
        unchanged);
    return new IndexResult(index, wanted.size(), added, removed, unchanged, null);
  }

  /**
   * Threshold = max(absolute value, current members × percentage), with integer division (SP500:
   * max(5, 503 × 5 / 100 = 25) = 25; NDX100: max(5, 5) = 5). No limit when there are no current
   * members (first import).
   */
  int removalLimit(int currentSize) {
    if (currentSize == 0) {
      return Integer.MAX_VALUE;
    }
    var u = props.universe();
    return Math.max(u.maxRemovalsPerSync(), currentSize * u.maxRemovalsPercent() / 100);
  }

  /**
   * Report-only comparison with SPY holdings; never changes data, and a failure only degrades the
   * summary text.
   */
  private String crossCheck(List<ConstituentEntry> sp500) {
    try {
      Set<String> spy = spyCheck.tickers();
      Set<String> wiki = new LinkedHashSet<>();
      sp500.forEach(e -> wiki.add(e.symbol()));
      Set<String> onlyWiki = new TreeSet<>(wiki);
      onlyWiki.removeAll(spy);
      Set<String> onlySpy = new TreeSet<>(spy);
      onlySpy.removeAll(wiki);
      return "SPY 交叉核对：持仓 "
          + spy.size()
          + " 只，仅在 Wikipedia "
          + onlyWiki.size()
          + " "
          + head(onlyWiki)
          + "，仅在 SPY "
          + onlySpy.size()
          + " "
          + head(onlySpy);
    } catch (RuntimeException e) {
      log.warn("SPY 交叉核对失败：{}", e.toString());
      return "SPY 交叉核对失败：" + e.getMessage();
    }
  }

  private static String head(Set<String> s) {
    List<String> l = new ArrayList<>(s);
    return l.size() <= 12 ? l.toString() : l.subList(0, 12) + "…";
  }

  /**
   * Fetches Futu static info in batches for PENDING / UNRESOLVED instruments; those Futu does not
   * know are marked UNRESOLVED. RESOLVED instruments are never re-checked here, which is why a
   * renamed symbol (PSKY → SKYD) needs {@link UnknownSymbolGuard} on the collection paths.
   */
  public String resolveStatics(JobContext ctx) {
    List<InstrumentRow> pending =
        instruments.findAll().stream().filter(r -> !"RESOLVED".equals(r.resolveStatus())).toList();
    if (pending.isEmpty()) {
      return "静态信息无需解析";
    }
    int resolved = 0;
    int unresolved = 0;
    int batch = Math.max(1, props.universe().staticBatchSize());
    for (int i = 0; i < pending.size(); i += batch) {
      List<InstrumentRow> chunk = pending.subList(i, Math.min(pending.size(), i + batch));
      ctx.progress("解析静态信息 " + (i + chunk.size()) + "/" + pending.size());
      Map<String, InstrumentRow> bySymbol = new HashMap<>();
      chunk.forEach(r -> bySymbol.put(r.symbol(), r));
      List<InstrumentStatic> statics;
      try {
        statics =
            gateway
                .staticInfo(chunk.stream().map(InstrumentRow::instrument).toList())
                .get(30, TimeUnit.SECONDS);
      } catch (Exception e) {
        ctx.partial("静态信息解析失败：" + e.getMessage());
        return "静态信息解析失败：" + e.getMessage() + "（已解析 " + resolved + "）";
      }
      for (InstrumentStatic s : statics) {
        InstrumentRow row = bySymbol.remove(s.instrument().symbol());
        if (row == null) {
          continue;
        }
        // Measured: Futu also returns a row for a symbol it does not know (name "unknown stock",
        // brokerId=0, delisted=true); it must not be treated as resolved
        if (s.brokerId() == 0) {
          instruments.markUnresolved(row.id());
          unresolved++;
        } else {
          instruments.updateStatic(row.id(), s);
          resolved++;
        }
      }
      for (InstrumentRow miss : bySymbol.values()) {
        instruments.markUnresolved(miss.id());
        unresolved++;
      }
    }
    return "静态信息：解析 " + resolved + "，富途不认识 " + unresolved;
  }
}
