package org.jdkxx.trader.core.marketdata.bars;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.SnapshotWindow;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.TradingDay;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Daily increment: refresh the trading calendar → count each instrument's missing trading days →
 * fill them through the subscription rotation (gap + overlap bars, idempotent overwrite) → refresh
 * price-adjustment factors for the pool and holdings (plus a capped slice of the universe).
 *
 * <p>Two things keep intraday prices out of the database: the rotation drops bars later than the
 * {@link SettledCutoff}, and "latest date" here only counts rows written after their close settled
 * ({@code latestSettledDates}), so a bar written intraday by an earlier manual run is treated as
 * missing and gets overwritten by the overlap re-fetch.
 */
public class DailyIncrementService {

  private static final Logger log = LoggerFactory.getLogger(DailyIncrementService.class);

  /**
   * US close is 16:00 ET; give the broker 15 minutes to finalize, and before that do not treat
   * "today" as a day that should already have its closing bar.
   */
  static final LocalTime CLOSE_SETTLED = SnapshotWindow.OPENS;

  private final MarketDataProperties props;
  private final MarketDataGateway gateway;
  private final TradingDayRepository tradingDays;
  private final DailyBarRepository bars;
  private final UniverseScope scope;
  private final RotationRefresher rotation;
  private final DeepBackfillService deep;
  private final Clock clock;
  private final ZoneId zone;

  public DailyIncrementService(
      MarketDataProperties props,
      MarketDataGateway gateway,
      TradingDayRepository tradingDays,
      DailyBarRepository bars,
      UniverseScope scope,
      RotationRefresher rotation,
      DeepBackfillService deep,
      Clock clock) {
    this.props = props;
    this.gateway = gateway;
    this.tradingDays = tradingDays;
    this.bars = bars;
    this.scope = scope;
    this.rotation = rotation;
    this.deep = deep;
    this.clock = clock;
    this.zone = ZoneId.of(props.zone());
  }

  public String run(JobContext ctx) throws Exception {
    ctx.progress("刷新交易日历");
    LocalDate today = LocalDate.now(clock.withZone(zone));
    List<TradingDay> days =
        gateway
            .tradingDays(Market.US, today.minusDays(60), today.plusDays(10))
            .get(30, TimeUnit.SECONDS);
    tradingDays.upsertAll(days);
    LocalDate expected =
        expectedLatestTradingDay(
            tradingDays.between(Market.US, today.minusDays(60), today),
            ZonedDateTime.now(clock.withZone(zone)));
    if (expected == null) {
      return "交易日历为空，未做增量";
    }
    List<LocalDate> calendar = tradingDays.between(Market.US, today.minusYears(5), expected);
    // Only count rows written after their close settled: a same-day bar written intraday is treated
    // as missing and gets overwritten by the overlap re-fetch
    Map<Long, LocalDate> latest = bars.latestSettledDates(zone, CLOSE_SETTLED);

    List<InstrumentRow> targets =
        props.refresh().universeIncrement()
            ? union(scope.universe(), scope.poolAndHoldings())
            : scope.poolAndHoldings();
    Map<Long, Integer> plan = new LinkedHashMap<>();
    for (InstrumentRow r : targets) {
      plan.put(r.id(), countFor(latest.get(r.id()), expected, calendar));
    }
    long need = plan.values().stream().filter(n -> n > 0).count();
    ctx.progress("增量目标 " + targets.size() + " 只，其中 " + need + " 只需要补到 " + expected);
    RotationRefresher.Result r =
        rotation.refresh(targets, row -> plan.getOrDefault(row.id(), 0), "增量", ctx);

    ctx.progress("刷新复权因子（池/持仓每日，全量每周）");
    String rehab = deep.refreshRehab(false, ctx);
    return "增量到 "
        + expected
        + "：目标 "
        + targets.size()
        + " 只，需补 "
        + r.instruments()
        + " 只，成功 "
        + r.ok()
        + "，失败 "
        + r.failed()
        + "，写入 K 线 "
        + r.bars()
        + (r.unsettled() > 0 ? "（丢弃未收盘 " + r.unsettled() + " 根）" : "")
        + "；"
        + rehab;
  }

  /**
   * The most recent trading day that should already have a closing bar at this moment: the last
   * calendar day not after today, where today only counts once it is strictly past 16:15 ET.
   * Returns null for an empty calendar. Shared by the write cutoff, the audits and the catch-up
   * check so they all agree on "the expected day".
   */
  public static LocalDate expectedLatestTradingDay(
      List<LocalDate> tradingDaysAsc, ZonedDateTime nowEt) {
    LocalDate today = nowEt.toLocalDate();
    boolean settled = nowEt.toLocalTime().isAfter(CLOSE_SETTLED);
    LocalDate best = null;
    for (LocalDate d : tradingDaysAsc) {
      if (d.isAfter(today) || (d.equals(today) && !settled)) {
        break;
      }
      best = d;
    }
    return best;
  }

  /**
   * Fetch as many bars as are missing plus overlap; an instrument never fetched gets the full
   * count. Missing days are counted against the trading calendar (last 5 years), not calendar days,
   * and the result is capped at 1000 (the most one getKL call returns).
   */
  int countFor(LocalDate latest, LocalDate expected, List<LocalDate> calendar) {
    if (latest == null) {
      return props.refresh().fullCount();
    }
    if (!latest.isBefore(expected)) {
      return 0;
    }
    int missing = 0;
    for (LocalDate d : calendar) {
      if (d.isAfter(latest) && !d.isAfter(expected)) {
        missing++;
      }
    }
    return missing == 0 ? 0 : Math.min(1000, missing + props.refresh().overlap());
  }

  private static List<InstrumentRow> union(List<InstrumentRow> a, List<InstrumentRow> b) {
    Map<Long, InstrumentRow> m = new LinkedHashMap<>();
    a.forEach(r -> m.put(r.id(), r));
    b.forEach(r -> m.put(r.id(), r));
    return List.copyOf(m.values());
  }
}
