package org.jdkxx.trader.core.account;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jdkxx.trader.core.account.ValuedPosition.PriceSource;
import org.jdkxx.trader.core.marketdata.SnapshotWindow;
import org.jdkxx.trader.core.marketdata.bars.DailyIncrementService;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.core.marketdata.universe.UnknownSymbolGuard;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.PoolRole;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.domain.ValuationSnapshot;
import org.jdkxx.trader.gateway.AccountGateway;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.account.AccountSnapshotRepository;
import org.jdkxx.trader.storage.account.AccountSnapshotRow;
import org.jdkxx.trader.storage.account.PositionSnapshotRow;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.PoolRepository;
import org.jdkxx.trader.storage.marketdata.PoolRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Body of the account snapshot job: IBKR positions and account summary -&gt; map to this system's
 * instruments -&gt; value at closing prices -&gt; sync the pool's HOLDING from the positions -&gt;
 * reconcile -&gt; store (re-taking a snapshot for the same day overwrites it).
 *
 * <p>Prices by priority: the day's bar close (BAR) -&gt; Futu snapshot price (SNAPSHOT, for
 * positions without a bar for the day, e.g. cash-management instruments that are only in snapshots
 * and not in the pool) -&gt; no price (NONE, reconciliation records WARN).
 *
 * <p>When does a snapshot price count as "the day's close": after the close, the current price in a
 * Futu snapshot freezes at the regular-session close and does not move during after-hours or
 * overnight until the next trading day, while the snapshot timestamp keeps following
 * after-hours/overnight. Measured on a Sunday at 20:52 US/Eastern for SPY: the timestamp was Sunday
 * and the price, 764.29, was exactly Friday's bar close. So <b>the timestamp cannot tell which day
 * the price belongs to</b>; the only test is whether it is still before 04:00 (US/Eastern) of the
 * next trading day.
 *
 * <p>All dates are US/Eastern ({@code zone}); the clock is UTC.
 */
public class AccountSnapshotService {

  private static final Logger log = LoggerFactory.getLogger(AccountSnapshotService.class);

  private final AccountProperties props;
  private final AccountPositions source;
  private final MarketDataGateway market;
  private final InstrumentRepository instruments;
  private final DailyBarRepository bars;
  private final PoolRepository pool;
  private final TradingDayRepository days;
  private final AccountSnapshotRepository snapshots;
  private final HoldingSyncService holdingSync;
  private final UnknownSymbolGuard unknownSymbols;
  private final Clock clock;
  private final ZoneId zone;

  /**
   * @param holdingSync when null the pool's HOLDING is not synced (snapshot and reconciliation
   *     only)
   */
  public AccountSnapshotService(
      AccountProperties props,
      String configuredAccount,
      BrokerGateway broker,
      AccountGateway accounts,
      MarketDataGateway market,
      InstrumentRepository instruments,
      DailyBarRepository bars,
      PoolRepository pool,
      TradingDayRepository days,
      AccountSnapshotRepository snapshots,
      HoldingSyncService holdingSync,
      UnknownSymbolGuard unknownSymbols,
      Clock clock,
      ZoneId zone) {
    this.props = props;
    this.source = new AccountPositions(configuredAccount, broker, accounts, instruments);
    this.market = market;
    this.instruments = instruments;
    this.bars = bars;
    this.pool = pool;
    this.days = days;
    this.snapshots = snapshots;
    this.holdingSync = holdingSync;
    this.unknownSymbols = unknownSymbols;
    this.clock = clock;
    this.zone = zone;
  }

  /**
   * @param force ignore the snapshot window and take the snapshot as of the latest closed trading
   *     day (only for verification in DEV; the caller enforces that)
   */
  public String run(JobContext ctx, boolean force) throws Exception {
    // asOf is the trading day this snapshot represents: inside the window it is the day whose close
    // just happened (SnapshotWindow), so a snapshot taken after midnight still belongs to the
    // previous trading day.
    ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
    LocalDate asOf =
        force
            ? latestSettledTradingDay(now)
            : SnapshotWindow.asOfDate(now, d -> days.isTradingDay(Market.US, d))
                .orElseThrow(() -> new IllegalStateException(SnapshotWindow.OUTSIDE));
    AccountKeys.requireSecret(props.keySecret());
    String accountId = source.chooseAccount();
    String mask = AccountKeys.mask(accountId);

    ctx.progress("取盈透持仓与资金汇总（" + mask + "）");
    List<Position> all = source.positions(accountId);
    AccountSummary summary = source.summary(accountId);
    // Rows with quantity 0 (IBKR can still list a position that was closed) are not holdings.
    List<Position> held = all.stream().filter(p -> p.quantity().signum() != 0).toList();

    ctx.progress("按 " + asOf + " 收盘估值 " + held.size() + " 条持仓");
    List<ValuedPosition> valued =
        value(
            held,
            asOf,
            // note: the holding sync below may create instruments, so ids are re-resolved
            // afterwards
            now);

    String syncNote = "";
    if (holdingSync != null) {
      ctx.progress("按持仓同步池里的 HOLDING");
      try {
        HoldingSyncService.Result s = holdingSync.syncFromSnapshot(valued);
        syncNote = "；持仓同步：" + s.summary();
        if (!s.errors().isEmpty() || s.plan().blocked() != null) {
          ctx.partial("持仓同步未完全执行");
        }
      } catch (RuntimeException e) {
        log.warn("持仓同步失败：{}", e.toString());
        syncNote = "；持仓同步失败：" + e.getMessage();
        ctx.partial("持仓同步失败");
      }
      valued = resolveNewlyCreated(valued);
    }

    List<PoolRow> members = pool.findAll();
    Map<Long, String> holdings = new LinkedHashMap<>();
    instruments
        .findByIds(
            members.stream()
                .filter(m -> m.role() == PoolRole.HOLDING)
                .map(PoolRow::instrumentId)
                .toList())
        .forEach(r -> holdings.put(r.id(), r.symbol()));
    Set<Long> benchmarks =
        members.stream()
            .filter(m -> m.role() == PoolRole.BENCHMARK)
            .map(PoolRow::instrumentId)
            .collect(Collectors.toSet());
    AccountReconciler.Result r =
        AccountReconciler.reconcile(
            summary,
            valued,
            holdings,
            benchmarks,
            props.valueTolerance(),
            props.identityTolerance());

    AccountSnapshotRow header =
        new AccountSnapshotRow(
            0,
            Broker.IBKR.name(),
            AccountKeys.key(props.keySecret(), Broker.IBKR, accountId),
            mask,
            asOf,
            clock.instant(),
            summary.currency(),
            summary.netLiquidation(),
            summary.totalCash(),
            summary.stockMarketValue(),
            summary.grossPositionValue(),
            summary.availableFunds(),
            summary.buyingPower(),
            summary.excessLiquidity(),
            summary.unrealizedPnl(),
            summary.realizedPnl(),
            summary.accruedDividend(),
            summary.accruedInterest(),
            r.positionValue(),
            valued.size(),
            r.status().name(),
            null,
            ctx.id());
    List<PositionSnapshotRow> rows =
        valued.stream()
            .map(
                v ->
                    new PositionSnapshotRow(
                        v.position().brokerRef(),
                        v.symbol(),
                        v.instrumentId(),
                        v.position().securityType(),
                        v.position().currency(),
                        v.position().exchange(),
                        v.position().quantity(),
                        v.position().averageCost(),
                        v.price(),
                        v.priceSource().name(),
                        v.marketValue(),
                        v.costBasis(),
                        v.unrealizedPnl(),
                        v.cashEquivalent()))
            .toList();
    snapshots.save(
        header,
        summary.raw(),
        r.checks().stream()
            .map(
                c ->
                    new AccountSnapshotRepository.ReconCheck(
                        c.name(), c.status().name(), c.detail()))
            .toList(),
        rows);

    if (r.status() != AccountReconciler.Status.OK) {
      ctx.partial("对账 " + r.status());
    }
    return "账户快照 "
        + asOf
        + "（"
        + mask
        + "）：持仓 "
        + held.size()
        + " 条，价格 "
        + sources(valued)
        + "，对账 "
        + r.status()
        + "；"
        + r.brief()
        + syncNote;
  }

  /**
   * The holding sync may create instruments for symbols not yet in the database (it resolves them
   * via Futu first). {@code valued} was computed <b>before</b> that, so those positions still have
   * a null {@code instrumentId}. Carrying on with it would (found in the 2026-09-25 full-project
   * review):
   *
   * <ul>
   *   <li>make reconciliation report the same instrument <b>both</b> as "position not in the
   *       database" and as "marked HOLDING in the pool but no longer held": the first looks at the
   *       null in valued, the second at the id just added to the pool, and the two never match;
   *   <li>mark the snapshot WARN and the job PARTIAL;
   *   <li>store {@code position_snapshot.instrument_id} as NULL <b>permanently</b>, so it can never
   *       be linked afterwards.
   * </ul>
   *
   * So after the sync the ones that are still null are resolved again. {@code
   * AccountPositions.instrumentId} binds the conId when it finds the instrument, so next time the
   * conId matches directly.
   */
  private List<ValuedPosition> resolveNewlyCreated(List<ValuedPosition> valued) {
    if (valued.stream().noneMatch(v -> v.instrumentId() == null)) {
      return valued;
    }
    List<ValuedPosition> out = new ArrayList<>(valued.size());
    for (ValuedPosition v : valued) {
      if (v.instrumentId() != null) {
        out.add(v);
        continue;
      }
      Long id = source.instrumentId(v.position());
      if (id == null) {
        out.add(v);
        continue;
      }
      log.info("{} 在持仓同步中建档，重解析得到 instrumentId={}", v.symbol(), id);
      out.add(new ValuedPosition(v.position(), id, v.price(), v.priceSource(), v.cashEquivalent()));
    }
    return out;
  }

  private LocalDate latestSettledTradingDay(ZonedDateTime now) {
    LocalDate d =
        DailyIncrementService.expectedLatestTradingDay(
            days.between(Market.US, now.toLocalDate().minusDays(45), now.toLocalDate()), now);
    if (d == null) {
      throw new IllegalStateException("交易日历为空，无法确定快照所属交易日");
    }
    return d;
  }

  List<ValuedPosition> value(List<Position> held, LocalDate asOf, ZonedDateTime now) {
    Set<String> cash =
        props.cashEquivalents().stream()
            .map(AccountPositions::normalize)
            .collect(Collectors.toSet());
    boolean snapshotIsClose = snapshotPriceIsClose(now, asOf);
    List<Long> ids = new ArrayList<>(held.size());
    for (Position p : held) {
      ids.add(source.instrumentId(p));
    }
    Map<Long, BigDecimal> closes =
        bars.closesOn(asOf, ids.stream().filter(Objects::nonNull).distinct().toList());

    // Only USD stocks without a bar on asOf need a Futu snapshot price (Futu cannot price other
    // asset types).
    List<String> needSnapshot = new ArrayList<>();
    for (int i = 0; i < held.size(); i++) {
      Long id = ids.get(i);
      if (AccountPositions.stockUsd(held.get(i)) && (id == null || !closes.containsKey(id))) {
        needSnapshot.add(AccountPositions.normalize(held.get(i).symbol()));
      }
    }
    Map<String, ValuationSnapshot> snaps = snapshotPrices(needSnapshot);

    List<ValuedPosition> out = new ArrayList<>(held.size());
    for (int i = 0; i < held.size(); i++) {
      Position p = held.get(i);
      Long id = ids.get(i);
      String symbol = AccountPositions.normalize(p.symbol());
      boolean isCash = cash.contains(symbol);
      if (id != null && closes.containsKey(id)) {
        out.add(new ValuedPosition(p, id, closes.get(id), PriceSource.BAR, isCash));
        continue;
      }
      ValuationSnapshot s = AccountPositions.stockUsd(p) ? snaps.get(symbol) : null;
      if (s != null && s.lastPrice() != null && s.lastPrice().signum() > 0 && snapshotIsClose) {
        out.add(new ValuedPosition(p, id, s.lastPrice(), PriceSource.SNAPSHOT, isCash));
      } else {
        log.info("{} 没有 {} 的价格（无当日 K 线，快照价缺失或已不是当日收盘）", symbol, asOf);
        out.add(new ValuedPosition(p, id, null, PriceSource.NONE, isCash));
      }
    }
    return out;
  }

  /**
   * Whether the snapshot price right now is still the close of {@code asOf}: it is until 04:00
   * (US/Eastern) of the next trading day. When the calendar has no next trading day, the next
   * calendar day is used, which errs on the conservative side (the window ends earlier, never
   * later).
   */
  boolean snapshotPriceIsClose(ZonedDateTime now, LocalDate asOf) {
    LocalDate next =
        days.between(Market.US, asOf.plusDays(1), asOf.plusDays(14)).stream()
            .filter(d -> d.isAfter(asOf))
            .findFirst()
            .orElse(asOf.plusDays(1));
    return now.isBefore(next.atTime(SnapshotWindow.CLOSES).atZone(zone));
  }

  /**
   * Fetches snapshot prices for positions without a bar for the day. One renamed position (unknown
   * to Futu) poisons the whole batch and leaves <b>every</b> position without a price, so
   * batch-level recovery is done here too: confirm the bad symbols -&gt; drop them -&gt; refetch
   * once, and the other positions get prices as usual.
   *
   * <p><b>The positions side passes {@code mayMark=false} and writes nothing</b>: a rename of
   * something we actually hold affects pricing and real-time subscriptions and deserves a human
   * look; the snapshot job should not quietly downgrade it. The dropped symbol follows the existing
   * "no price (NONE)" path: reconciliation records WARN and the account audit reports the count of
   * unpriced positions, so the downgrade stays visible even though nothing is written.
   */
  private Map<String, ValuationSnapshot> snapshotPrices(List<String> symbols) {
    if (symbols.isEmpty()) {
      return Map.of();
    }
    List<Instrument> wanted = symbols.stream().distinct().map(Instrument::us).toList();
    try {
      return index(AccountPositions.await(market.snapshots(wanted)));
    } catch (Exception e) {
      UnknownSymbolGuard.Outcome out = unknownSymbols.inspect(wanted, e, false).orElse(null);
      if (out != null && out.canRetry()) {
        List<Instrument> kept =
            wanted.stream()
                .filter(i -> !out.exclude().contains(i.symbol().toUpperCase(Locale.ROOT)))
                .toList();
        if (!kept.isEmpty()) {
          try {
            Map<String, ValuationSnapshot> m =
                index(AccountPositions.await(market.snapshots(kept)));
            log.warn(
                "持仓里有富途不认识的代码 {}，剔除后重取成功：{} 条有价、{} 条按缺价处理。" + "持仓改名要人确认，所以不自动标 UNRESOLVED",
                out.exclude(),
                m.size(),
                wanted.size() - kept.size());
            return m;
          } catch (Exception retry) {
            log.warn("剔除 {} 只未知代码后重取持仓快照仍失败：{}", wanted.size() - kept.size(), retry.toString());
          }
        }
      }
      log.warn("富途快照取价失败，{} 条持仓记为缺价：{}", symbols.size(), out == null ? e.toString() : out.detail());
      return Map.of();
    }
  }

  private static Map<String, ValuationSnapshot> index(List<ValuationSnapshot> got) {
    Map<String, ValuationSnapshot> m = new LinkedHashMap<>();
    got.forEach(v -> m.putIfAbsent(v.instrument().symbol(), v));
    return m;
  }

  private static String sources(List<ValuedPosition> valued) {
    Map<PriceSource, Long> n = new EnumMap<>(PriceSource.class);
    valued.forEach(v -> n.merge(v.priceSource(), 1L, Long::sum));
    return "K线 "
        + n.getOrDefault(PriceSource.BAR, 0L)
        + " / 快照 "
        + n.getOrDefault(PriceSource.SNAPSHOT, 0L)
        + " / 缺价 "
        + n.getOrDefault(PriceSource.NONE, 0L);
  }
}
