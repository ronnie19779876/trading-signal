package org.jdkxx.trader.core.selection;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.selection.ValuationMetric;
import org.jdkxx.trader.domain.selection.ValuationPoint;
import org.jdkxx.trader.domain.selection.ValuationRange;
import org.jdkxx.trader.gateway.NotConnectedException;
import org.jdkxx.trader.gateway.SelectionDataGateway;
import org.jdkxx.trader.storage.marketdata.FinancialRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.selection.SelectionEarningsRepository;
import org.jdkxx.trader.storage.selection.SelectionValuationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Daily and weekly maintenance for quant selection (§25.7).
 *
 * <ul>
 *   <li>Daily: refresh announcement dates for pool and holdings (3.5.0, holdings risk panel), then
 *       extend the valuation series from the valuation snapshot, then for names that reported in
 *       the last few days, and those whose statements are still incomplete within 45 days (3.4.6),
 *       refetch the latest 4 quarterly reports and 3 months of valuation, then score, then
 *       recompute the simulated trades (§26);
 *   <li>Weekly: refresh announcement dates for all names; security type, valuation history,
 *       quarterly reports and IBKR prices only fill what is missing (this is how newly added
 *       constituents get their inputs).
 * </ul>
 *
 * A failed refetch only records a partial failure and scoring goes ahead anyway (with what is
 * already in the database; the audit shows how fresh it is).
 */
public class SelectionDailyService {

  private static final Logger log = LoggerFactory.getLogger(SelectionDailyService.class);

  /**
   * Refetch for this many days after an announcement: covers weekends and the occasional failure
   */
  static final int REFRESH_LOOKBACK_DAYS = 6;

  /**
   * Within this many days after an announcement, a quarter whose three statements have not all
   * arrived keeps being refetched daily (3.4.6): Futu delivers the income statement first and the
   * balance sheet and cash flow statement later (2026-10-22 measured: JPM / BAC / GS had no balance
   * sheet a week after announcing); previously nothing was fetched once the 6 days had passed. 45
   * days matches the statutory 10-Q deadline (40~45 days after period end); quarters still
   * incomplete after that are listed by the audit {@code financialsComplete}.
   */
  static final int COMPLETE_LOOKBACK_DAYS = 45;

  static final int REFRESH_PERIODS = 4;
  private static final List<FinancialStatement> STATEMENTS =
      List.of(
          FinancialStatement.INCOME,
          FinancialStatement.BALANCE_SHEET,
          FinancialStatement.CASH_FLOW);

  private final SelectionTargets targets;
  private final SelectionValuationRepository valuations;
  private final SelectionEarningsRepository earnings;
  private final FinancialRepository financials;
  private final SelectionDataGateway fundamentals;
  private final SelectionScoringService scoring;
  private final SelectionBackfillService backfill;
  private final SelectionSimService sim;

  public SelectionDailyService(
      SelectionTargets targets,
      SelectionValuationRepository valuations,
      SelectionEarningsRepository earnings,
      FinancialRepository financials,
      SelectionDataGateway fundamentals,
      SelectionScoringService scoring,
      SelectionBackfillService backfill,
      SelectionSimService sim) {
    this.targets = targets;
    this.valuations = valuations;
    this.earnings = earnings;
    this.financials = financials;
    this.fundamentals = fundamentals;
    this.scoring = scoring;
    this.backfill = backfill;
    this.sim = sim;
  }

  public String daily(LocalDate date, JobContext ctx) {
    List<InstrumentRow> all = targets.all();
    String events = refreshPoolAndHoldingEvents(ctx);
    ctx.progress("估值快照续接");
    int appended =
        valuations.appendFromSnapshot(date, all.stream().map(InstrumentRow::id).toList());

    Set<Long> recent =
        earnings.instrumentsWithPubBetween(date.minusDays(REFRESH_LOOKBACK_DAYS), date);
    Set<Long> incomplete = new java.util.HashSet<>();
    for (SelectionEarningsRepository.IncompleteFinancials f :
        earnings.incompleteFinancials(date.minusDays(COMPLETE_LOOKBACK_DAYS), date)) {
      incomplete.add(f.instrumentId());
    }
    List<InstrumentRow> refresh =
        all.stream().filter(r -> recent.contains(r.id()) || incomplete.contains(r.id())).toList();
    long catchingUp = refresh.stream().filter(r -> !recent.contains(r.id())).count();
    int ok = 0;
    int failed = 0;
    for (int i = 0; i < refresh.size(); i++) {
      InstrumentRow r = refresh.get(i);
      ctx.progress("补取新公告 " + (i + 1) + "/" + refresh.size() + " " + r.symbol());
      try {
        for (FinancialStatement st : STATEMENTS) {
          List<FinancialReport> reports =
              fundamentals
                  .quarterlyFinancials(r.instrument(), st, REFRESH_PERIODS)
                  .get(90, TimeUnit.SECONDS);
          financials.upsertAll(r.id(), reports);
        }
        for (ValuationMetric m : ValuationMetric.values()) {
          List<ValuationPoint> points =
              fundamentals
                  .valuationHistory(r.instrument(), m, ValuationRange.MONTH3)
                  .get(90, TimeUnit.SECONDS);
          valuations.upsertHistory(r.id(), m, points);
        }
        ok++;
      } catch (Exception e) {
        Throwable c = unwrap(e);
        failed++;
        log.warn("量化选股补取新公告 {} 失败：{}", r.symbol(), c.toString());
        if (c instanceof NotConnectedException) {
          ctx.partial("网关未连接，新公告补取停在 " + r.symbol());
          break;
        }
      }
    }
    if (failed > 0) {
      ctx.partial("新公告补取失败 " + failed + " 只");
    }
    String scored = scoring.scoreDay(date, ctx);
    // Simulated trading runs after scoring: it only uses daily bars and does not depend on the
    // scores, and its failure does not affect scoring (the audit check simUpdated flags it, and the
    // 22:30 catch-up runs it again)
    String simulated;
    try {
      simulated = sim.update(date, ctx);
    } catch (RuntimeException e) {
      log.warn("模拟交易重算失败：{}", e.toString());
      ctx.partial("模拟交易重算失败：" + e.getMessage());
      simulated = "模拟交易重算失败";
    }
    return events
        + "；估值续接 "
        + appended
        + " 只；新公告补取 "
        + ok
        + "/"
        + refresh.size()
        + (catchingUp > 0 ? "（其中报表未到齐续取 " + catchingUp + " 只）" : "")
        + "；"
        + scored
        + "；"
        + simulated;
  }

  /**
   * Announcement dates for pool and holdings are refreshed daily (scheduled dates can move at short
   * notice); a failure only records a partial failure, and the panel keeps using what is already in
   * the database.
   */
  String refreshPoolAndHoldingEvents(JobContext ctx) {
    List<InstrumentRow> rows = targets.poolAndHoldings();
    int ok = 0;
    for (int i = 0; i < rows.size(); i++) {
      InstrumentRow r = rows.get(i);
      ctx.progress("池与持仓公告日 " + (i + 1) + "/" + rows.size() + " " + r.symbol());
      try {
        backfill.earnings(r);
        ok++;
      } catch (Exception e) {
        Throwable c = unwrap(e);
        log.warn("刷新公告日 {} 失败：{}", r.symbol(), c.toString());
        if (c instanceof NotConnectedException) {
          ctx.partial("网关未连接，公告日刷新停在 " + r.symbol());
          break;
        }
      }
    }
    if (ok < rows.size()) {
      ctx.partial("池与持仓公告日刷新失败 " + (rows.size() - ok) + " 只");
    }
    return "池与持仓公告日 " + ok + "/" + rows.size();
  }

  /**
   * Weekly: refresh announcement dates for all names (to pick up next quarter's scheduled date),
   * then run one <b>fill-missing-only</b> backfill each for security type, valuation history,
   * quarterly reports and IBKR prices - completed ones are skipped, so normally almost no requests
   * go out; names newly added by the constituent sync get their deep inputs this way (3.2.0 lacked
   * this, so new members never got 10 years of valuation and 50 quarterly reports).
   */
  public String weekly(JobContext ctx) {
    List<String> out = new java.util.ArrayList<>();
    out.add(backfill.run(BackfillPart.EARNINGS, List.of(), true, ctx));
    for (BackfillPart p : WEEKLY_FILL) {
      if (ctx.cancelled()) {
        break;
      }
      out.add(backfill.run(p, List.of(), false, ctx));
    }
    return String.join("；", out);
  }

  /** Parts the weekly job only fills where missing; IBKR goes last (slowest pacing). */
  static final List<BackfillPart> WEEKLY_FILL =
      List.of(
          BackfillPart.TYPES, BackfillPart.VALUATION, BackfillPart.FINANCIALS, BackfillPart.PRICES);

  private static Throwable unwrap(Throwable t) {
    Throwable c = t;
    while ((c instanceof ExecutionException || c instanceof CompletionException)
        && c.getCause() != null) {
      c = c.getCause();
    }
    return c;
  }
}
