package org.jdkxx.trader.core.marketdata.fundamentals;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.SnapshotWindow;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.core.marketdata.universe.UnknownSymbolGuard;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.ValuationSnapshot;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.marketdata.ValuationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Valuation snapshot job: universe ∪ pool ∪ holdings, once per trading day after the close. The
 * snapshot API takes at most 400 instruments per call and uses neither subscription nor history-bar
 * quota, so 500-odd instruments need only two calls and do not compete with the already-stable
 * daily bar pipeline.
 *
 * <p>Market cap and P/E move with the live price, so they are only taken inside the close window
 * (16:15 US/Eastern on a trading day to 04:00 the next morning, the same basis as the account
 * snapshot). Before 2.0.2 the date was the "trading day that should have a closing bar": a manual
 * refresh intraday overwrote the previous trading day's close-based row with live prices, and that
 * could not be recovered.
 *
 * <p>The as-of date is fixed once at the start of the run, so every batch of one run is written to
 * the same day. Negative P/E for loss-making companies is real data and is stored as is.
 */
public class ValuationSnapshotService {

  private static final Logger log = LoggerFactory.getLogger(ValuationSnapshotService.class);

  public static final String OUTSIDE =
      "现在不在估值快照窗口（交易日美东 16:15 至次日 04:00）。" + "窗口外取到的是实时价，会覆盖上一交易日按收盘算的市值与市盈率";

  private final MarketDataProperties props;
  private final UniverseScope scope;
  private final MarketDataGateway gateway;
  private final ValuationRepository valuations;
  private final TradingDayRepository tradingDays;
  private final UnknownSymbolGuard unknownSymbols;
  private final Clock clock;

  public ValuationSnapshotService(
      MarketDataProperties props,
      UniverseScope scope,
      MarketDataGateway gateway,
      ValuationRepository valuations,
      TradingDayRepository tradingDays,
      UnknownSymbolGuard unknownSymbols,
      Clock clock) {
    this.props = props;
    this.scope = scope;
    this.gateway = gateway;
    this.valuations = valuations;
    this.tradingDays = tradingDays;
    this.unknownSymbols = unknownSymbols;
    this.clock = clock;
  }

  /** Which trading day a snapshot taken now belongs to; empty outside the close window. */
  public Optional<LocalDate> asOfDate() {
    ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZoneId.of(props.zone())));
    return SnapshotWindow.asOfDate(now, d -> tradingDays.isTradingDay(Market.US, d));
  }

  public String run(JobContext ctx) throws Exception {
    Optional<LocalDate> asOf = asOfDate();
    if (asOf.isEmpty()) {
      return OUTSIDE + "；未取快照";
    }
    LocalDate tradeDate = asOf.get();

    Map<Instrument, Long> ids = new LinkedHashMap<>();
    for (InstrumentRow r : union(scope.universe(), scope.poolAndHoldings())) {
      ids.put(new Instrument(r.market(), r.symbol()), r.id());
    }
    List<Instrument> targets = List.copyOf(ids.keySet());
    int batch =
        Math.min(props.fundamentals().snapshotBatchSize(), MarketDataGateway.SNAPSHOT_BATCH);
    ctx.progress(
        "估值快照 " + targets.size() + " 只，分 " + ((targets.size() + batch - 1) / batch) + " 批");

    int written = 0;
    int failed = 0;
    for (int from = 0; from < targets.size(); from += batch) {
      if (ctx.cancelled()) {
        ctx.partial("已取消");
        break;
      }
      List<Instrument> slice = targets.subList(from, Math.min(from + batch, targets.size()));
      Fetched got = fetch(slice, ctx);
      written += valuations.upsertAll(ids, got.snapshots(), tradeDate);
      failed += got.failed();
      ctx.progress(
          "估值快照 "
              + Math.min(from + batch, targets.size())
              + "/"
              + targets.size()
              + "（写入 "
              + written
              + "）");
    }
    return "估值快照 " + tradeDate + "：目标 " + targets.size() + " 只，写入 " + written + "，失败 " + failed;
  }

  private record Fetched(List<ValuationSnapshot> snapshots, int failed) {}

  /**
   * Fetches one batch of snapshots; when rejected, try batch-level recovery once: identify the
   * symbols Futu does not know → drop them → fetch again once. On 2026-10-06 PSKY cost 400
   * valuations here in one go; with recovery it only costs itself.
   */
  private Fetched fetch(List<Instrument> slice, JobContext ctx) {
    try {
      return new Fetched(gateway.snapshots(slice).get(60, TimeUnit.SECONDS), 0);
    } catch (Exception e) {
      UnknownSymbolGuard.Outcome out = unknownSymbols.inspect(slice, e, true).orElse(null);
      if (out != null && out.canRetry()) {
        List<Instrument> kept =
            slice.stream()
                .filter(i -> !out.exclude().contains(i.symbol().toUpperCase(Locale.ROOT)))
                .toList();
        int dropped = slice.size() - kept.size();
        if (!kept.isEmpty()) {
          try {
            List<ValuationSnapshot> got = gateway.snapshots(kept).get(60, TimeUnit.SECONDS);
            ctx.partial(out.detail());
            log.warn("剔除 {} 只未知代码后重取快照成功，本批 {} 只", dropped, kept.size());
            return new Fetched(got, dropped);
          } catch (Exception retry) {
            log.warn("剔除 {} 只未知代码后重取快照仍失败：{}", dropped, retry.toString());
          }
        }
      }
      log.warn("估值快照一批失败（{} 只）：{}", slice.size(), e.toString());
      ctx.partial("一批快照失败：" + (out == null ? e.getMessage() : out.detail()));
      return new Fetched(List.of(), slice.size());
    }
  }

  private static List<InstrumentRow> union(List<InstrumentRow> a, List<InstrumentRow> b) {
    Map<Long, InstrumentRow> m = new LinkedHashMap<>();
    a.forEach(r -> m.put(r.id(), r));
    b.forEach(r -> m.put(r.id(), r));
    return new ArrayList<>(m.values());
  }
}
