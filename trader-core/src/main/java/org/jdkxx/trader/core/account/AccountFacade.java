package org.jdkxx.trader.core.account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jdkxx.trader.core.marketdata.Jobs;
import org.jdkxx.trader.core.marketdata.SnapshotWindow;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.storage.account.AccountSnapshotRepository;
import org.jdkxx.trader.storage.account.AccountSnapshotRow;
import org.jdkxx.trader.storage.account.PositionSnapshotRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;

/**
 * Entry point for account and positions: submit snapshot jobs, query snapshots (with the change
 * against the previous snapshot).
 */
public class AccountFacade {

  /**
   * Change against the previous snapshot of the same account, computed at query time and not
   * stored.
   *
   * @param netLiquidationChange net liquidation change, <b>including deposits and withdrawals</b>
   *     (phase 3 does not separate them)
   * @param positionPnl sum over positions <b>with the same quantity in both snapshots</b> of
   *     quantity x (current price - previous price); positions without a price are left out
   * @param positionsChanged the position set or a quantity changed (trades, or a split/reverse
   *     split), so positionPnl is only an approximation
   * @param excludedPositions number of positions left out of positionPnl because their quantity
   *     changed
   */
  public record DailyChange(
      LocalDate previousDate,
      BigDecimal netLiquidationChange,
      BigDecimal positionPnl,
      boolean positionsChanged,
      int excludedPositions) {}

  public record SnapshotView(
      AccountSnapshotRow snapshot, List<PositionSnapshotRow> positions, DailyChange change) {}

  private final JobService jobs;
  private final AccountSnapshotService service;
  private final AccountSnapshotRepository snapshots;
  private final TradingDayRepository days;
  private final String environment;
  private final Clock clock;
  private final ZoneId zone;

  public AccountFacade(
      JobService jobs,
      AccountSnapshotService service,
      AccountSnapshotRepository snapshots,
      TradingDayRepository days,
      String environment,
      Clock clock,
      ZoneId zone) {
    this.jobs = jobs;
    this.service = service;
    this.snapshots = snapshots;
    this.days = days;
    this.environment = environment;
    this.clock = clock;
    this.zone = zone;
  }

  /**
   * Submits an account snapshot job. Outside the window it is rejected right away instead of
   * submitting a job that is bound to fail. force=true ignores the window and snapshots as of the
   * latest closed trading day; it is only allowed in DEV, because in production it would pass off
   * positions taken at a non-close moment as some trading day's closing snapshot.
   */
  public long snapshot(String trigger, boolean force) {
    if (force) {
      if (!"DEV".equals(environment)) {
        throw new IllegalStateException("force=true 只允许在开发环境（trader.environment=DEV）使用");
      }
    } else if (SnapshotWindow.asOfDate(
            ZonedDateTime.now(clock.withZone(zone)), d -> days.isTradingDay(Market.US, d))
        .isEmpty()) {
      throw new IllegalStateException(SnapshotWindow.OUTSIDE);
    }
    return jobs.submit(Jobs.ACCOUNT_SNAPSHOT, trigger, ctx -> service.run(ctx, force));
  }

  public Optional<SnapshotView> latest() {
    return snapshots
        .latest()
        .map(
            s -> {
              List<PositionSnapshotRow> positions = snapshots.positions(s.id());
              DailyChange change =
                  snapshots
                      .previous(s.accountKey(), s.asOfDate())
                      .map(p -> change(p, snapshots.positions(p.id()), s, positions))
                      .orElse(null);
              return new SnapshotView(s, positions, change);
            });
  }

  public List<AccountSnapshotRow> between(LocalDate from, LocalDate to) {
    return snapshots.between(from, to);
  }

  static DailyChange change(
      AccountSnapshotRow prev,
      List<PositionSnapshotRow> prevPositions,
      AccountSnapshotRow cur,
      List<PositionSnapshotRow> curPositions) {
    BigDecimal nav =
        prev.netLiquidation() == null || cur.netLiquidation() == null
            ? null
            : cur.netLiquidation().subtract(prev.netLiquidation());
    Map<String, PositionSnapshotRow> before = new HashMap<>();
    prevPositions.forEach(p -> before.put(p.brokerRef(), p));
    BigDecimal pnl = BigDecimal.ZERO;
    boolean changed = prevPositions.size() != curPositions.size();
    int excluded = 0;
    for (PositionSnapshotRow c : curPositions) {
      PositionSnapshotRow p = before.get(c.brokerRef());
      if (p == null) {
        changed = true;
        excluded++;
        continue;
      }
      // A position whose quantity changed is left out: in snapshots a split/reverse split looks the
      // same as a trade, and on a split day quantity and price change proportionally <b>at the same
      // time</b>.
      // The old "previous quantity x price difference" produced errors of the wrong order of
      // magnitude (3:1 split:
      // 10 x (100 - 300) = -2000) and was also flagged as "traded today", misreporting the split as
      // a trade (found in the 2026-09-25 full-project review). daily_bar is never price-adjusted
      // and the broker reports post-split quantities; there is no information here to align the
      // two. Rather than guess, do not count it, and report honestly how many were left out.
      if (p.quantity().compareTo(c.quantity()) != 0) {
        changed = true;
        excluded++;
        continue;
      }
      if (p.price() != null && c.price() != null) {
        pnl = pnl.add(p.quantity().multiply(c.price().subtract(p.price())));
      }
    }
    return new DailyChange(
        prev.asOfDate(), nav, pnl.setScale(4, RoundingMode.HALF_UP), changed, excluded);
  }
}
