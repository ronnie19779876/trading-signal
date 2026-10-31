package org.jdkxx.trader.core.selection;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.domain.selection.SelectionModel;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.selection.SelectionPriceSeamRepository;
import org.jdkxx.trader.storage.selection.SelectionScoreRepository;
import org.jdkxx.trader.storage.selection.SelectionSimRepository;
import org.jdkxx.trader.storage.selection.SimTradeRow;

/**
 * Public entry point of quant selection (controllers only talk to this). Jobs go through the shared
 * JobService and run serially, never concurrently with market data or signal jobs.
 */
public class SelectionFacade {

  private final JobService jobs;
  private final SelectionTargets targets;
  private final SelectionBackfillService backfill;
  private final SelectionInputsService inputs;
  private final SelectionPriceSeamRepository seams;
  private final SelectionScoringService scoring;
  private final SelectionDailyService daily;
  private final SelectionAuditService audit;
  private final SelectionScoreRepository scores;
  private final SelectionSimService sim;

  public SelectionFacade(
      JobService jobs,
      SelectionTargets targets,
      SelectionBackfillService backfill,
      SelectionInputsService inputs,
      SelectionPriceSeamRepository seams,
      SelectionScoringService scoring,
      SelectionDailyService daily,
      SelectionAuditService audit,
      SelectionScoreRepository scores,
      SelectionSimService sim) {
    this.jobs = jobs;
    this.targets = targets;
    this.backfill = backfill;
    this.inputs = inputs;
    this.seams = seams;
    this.scoring = scoring;
    this.daily = daily;
    this.audit = audit;
    this.scores = scores;
    this.sim = sim;
  }

  public long daily(String trigger, LocalDate date) {
    return jobs.submit(SelectionJobs.DAILY, trigger, ctx -> daily.daily(date, ctx));
  }

  public long weekly(String trigger) {
    return jobs.submit(SelectionJobs.WEEKLY, trigger, daily::weekly);
  }

  /**
   * Manually scores one day (scoring only; no valuation extension and no new-announcement refetch).
   */
  public long score(String trigger, LocalDate date) {
    return jobs.submit(SelectionJobs.SCORE, trigger, ctx -> scoring.scoreDay(date, ctx));
  }

  public long replay(String trigger, LocalDate from, LocalDate to) {
    if (from.isAfter(to)) {
      throw new IllegalArgumentException("from 不能晚于 to");
    }
    return jobs.submit(SelectionJobs.REPLAY, trigger, ctx -> scoring.replay(from, to, ctx));
  }

  /** Full table for one day; date defaults to the latest score date. */
  public List<SelectionScoreRepository.ScoreView> day(
      LocalDate date, String sector, String rating) {
    LocalDate d =
        date != null
            ? date
            : scores
                .latestDate(SelectionModel.VERSION)
                .orElseThrow(() -> new NoSuchElementException("还没有任何评分结果"));
    return scores.day(
        SelectionModel.VERSION, d, sector, rating == null ? null : rating.toUpperCase(Locale.ROOT));
  }

  public List<SelectionScoreRepository.ScoreView> history(
      String symbol, LocalDate from, LocalDate to) {
    InstrumentRow row =
        targets.all().stream()
            .filter(r -> r.symbol().equalsIgnoreCase(symbol))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("不在量化选股范围内：" + symbol));
    return scores.history(SelectionModel.VERSION, row.id(), from, to);
  }

  /** Most recent score dates, newest first; limit is clamped to 1~5000. */
  public List<LocalDate> days(int limit) {
    return scores.days(SelectionModel.VERSION, Math.clamp(limit, 1, 5000));
  }

  public BarAuditService.Report audit(LocalDate date) {
    return audit.audit(date);
  }

  /**
   * Submits a backfill job and returns job_run.id. Symbols outside the universe fail immediately
   * and nothing is submitted.
   */
  public long backfill(String trigger, BackfillPart part, List<String> symbols, boolean force) {
    targets.only(symbols);
    return jobs.submit(
        SelectionJobs.BACKFILL, trigger, ctx -> backfill.run(part, symbols, force, ctx));
  }

  public SelectionInputsService.Report inputs() {
    return inputs.report();
  }

  public List<SelectionPriceSeamRepository.SeamRow> seams(boolean failedOnly) {
    return seams.findAll(failedOnly);
  }

  // ---- simulated trading (§26): add and close are synchronous writes; recompute and weekly report
  // go through the job queue

  public SelectionSimService.Overview simOverview() {
    return sim.overview();
  }

  public SelectionSimService.Preview simPreview(String symbol) {
    return sim.preview(symbol);
  }

  public SimTradeRow simAdd(String symbol) {
    return sim.add(symbol);
  }

  public SimTradeRow simClose(long id, String note) {
    return sim.requestClose(id, note);
  }

  public List<SimTradeRow> simTrades(String status) {
    return sim.trades(status);
  }

  public SelectionSimService.TradeDetail simTrade(long id) {
    return sim.trade(id);
  }

  public List<SelectionSimRepository.Event> simEvents(int limit) {
    return sim.events(limit);
  }

  public List<SelectionSimRepository.EquityRow> simEquity(LocalDate from, LocalDate to) {
    return sim.equity(from, to);
  }

  public List<SelectionSimRepository.WeeklyRow> simWeeklies(int limit) {
    return sim.weeklies(limit);
  }

  public SelectionSimRepository.WeeklyRow simWeeklyRow(LocalDate weekEnd) {
    return sim.weeklyRow(weekEnd);
  }

  /** Recomputes through `through` (default = the latest settled trading day). */
  public long simUpdate(String trigger, LocalDate through) {
    return jobs.submit(
        SelectionJobs.SIM_UPDATE,
        trigger,
        ctx -> sim.update(through != null ? through : cutoff(), ctx));
  }

  public long simWeekly(String trigger, LocalDate weekEnd, boolean force) {
    return jobs.submit(SelectionJobs.SIM_WEEKLY, trigger, ctx -> sim.weekly(weekEnd, force, ctx));
  }

  private LocalDate cutoff() {
    return audit.cutoff();
  }
}
