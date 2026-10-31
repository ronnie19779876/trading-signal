package org.jdkxx.trader.core.marketdata.bars;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.TradingDay;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;

/**
 * Trading calendar backfill: two stitched segments, idempotent, finishes in a few seconds.
 *
 * <p><b>Broker segment</b>: one request for everything the broker can give. Measured: the broker
 * only goes back to 2016-09-12; requesting 21 years and 27 years returns exactly the same 2591
 * days, so that is a hard server-side boundary, not paging.
 *
 * <p><b>Derived segment</b>: earlier dates are derived from existing daily bars. Pool, holdings and
 * benchmarks are large caps that trade every trading day, so the union of the dates they appear on
 * is that period's trading calendar. Derived rows are marked DERIVED and only fill gaps; they never
 * overwrite broker data.
 */
public class CalendarBackfillService {

  /**
   * Start date of the broker request. Generous on purpose; how far back it really goes is up to the
   * broker.
   */
  static final LocalDate BROKER_REQUEST_FROM = LocalDate.of(2010, 1, 1);

  /**
   * How many days past today the calendar must reach (the increment needs it to decide "the latest
   * trading day that should have a closing bar").
   */
  static final int FORWARD_DAYS = 10;

  /**
   * Minimum number of instruments that must have traded on a day for it to count as a trading day
   * when deriving. Measured: Futu left dirty SPY bars on three US market holidays (turnover 0), so
   * a plain union of "has a bar" would include those holidays. Real trading days have 13+
   * instruments trading together, so 2 blocks the dirty data while staying far from the real value.
   */
  static final int MIN_INSTRUMENTS_PER_DAY = 2;

  private final MarketDataProperties props;
  private final MarketDataGateway gateway;
  private final TradingDayRepository tradingDays;
  private final DailyBarRepository bars;
  private final UniverseScope scope;
  private final Clock clock;

  public CalendarBackfillService(
      MarketDataProperties props,
      MarketDataGateway gateway,
      TradingDayRepository tradingDays,
      DailyBarRepository bars,
      UniverseScope scope,
      Clock clock) {
    this.props = props;
    this.gateway = gateway;
    this.tradingDays = tradingDays;
    this.bars = bars;
    this.scope = scope;
    this.clock = clock;
  }

  public String run(JobContext ctx) throws Exception {
    LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(props.zone())));

    ctx.progress("向券商取交易日历");
    List<TradingDay> fromBroker =
        gateway
            .tradingDays(Market.US, BROKER_REQUEST_FROM, today.plusDays(FORWARD_DAYS))
            .get(30, TimeUnit.SECONDS);
    tradingDays.upsertAll(fromBroker);
    LocalDate brokerEarliest =
        fromBroker.stream().map(TradingDay::date).min(LocalDate::compareTo).orElse(null);
    ctx.progress(
        "券商段 "
            + fromBroker.size()
            + " 天"
            + (brokerEarliest == null ? "" : "，最早 " + brokerEarliest));

    int derived = 0;
    if (brokerEarliest != null) {
      Set<Long> deep = new LinkedHashSet<>();
      scope.poolAndHoldings().forEach(r -> deep.add(r.id()));
      LocalDate from = earliestBar(deep);
      if (from != null && from.isBefore(brokerEarliest)) {
        ctx.progress("反推 " + from + " 至 " + brokerEarliest + " 的交易日（券商取不到）");
        List<LocalDate> dates =
            bars.distinctTradeDates(deep, from, brokerEarliest, MIN_INSTRUMENTS_PER_DAY);
        derived = tradingDays.insertDerived(Market.US, dates);
        ctx.progress("反推段候选 " + dates.size() + " 天，新增 " + derived);
      }
    }

    TradingDayRepository.Coverage c = tradingDays.coverage(Market.US);
    return "交易日历：券商段 "
        + fromBroker.size()
        + " 天"
        + (brokerEarliest == null ? "" : "（最早 " + brokerEarliest + "）")
        + "，反推新增 "
        + derived
        + " 天；现覆盖 "
        + c.earliest()
        + " 至 "
        + c.latest()
        + " 共 "
        + c.days()
        + " 天（券商 "
        + c.fromBroker()
        + "，反推 "
        + c.derived()
        + "）";
  }

  /** Earliest bar among the deep instruments; no deep instruments means nothing to derive. */
  private LocalDate earliestBar(Set<Long> instrumentIds) {
    if (instrumentIds.isEmpty()) {
      return null;
    }
    return bars.coverageByInstrument().stream()
        .filter(c -> instrumentIds.contains(c.instrumentId()))
        .map(DailyBarRepository.InstrumentCoverage::earliest)
        .filter(java.util.Objects::nonNull)
        .min(LocalDate::compareTo)
        .orElse(null);
  }

  /**
   * Earliest bar date of the deep instruments; the audit uses it to tell whether the derived
   * segment is complete.
   */
  public LocalDate earliestDeepBar() {
    Set<Long> ids =
        scope.poolAndHoldings().stream()
            .map(InstrumentRow::id)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    return earliestBar(ids);
  }
}
