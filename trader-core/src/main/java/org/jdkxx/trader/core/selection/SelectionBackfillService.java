package org.jdkxx.trader.core.selection;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentInfo;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.domain.selection.EarningsEvent;
import org.jdkxx.trader.domain.selection.PriceSeam;
import org.jdkxx.trader.domain.selection.SplitAdjustedBar;
import org.jdkxx.trader.domain.selection.ValuationMetric;
import org.jdkxx.trader.domain.selection.ValuationPoint;
import org.jdkxx.trader.gateway.NotConnectedException;
import org.jdkxx.trader.gateway.PriceHistoryGateway;
import org.jdkxx.trader.gateway.ReferenceDataGateway;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.SelectionDataGateway;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.FinancialRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.selection.SelectionBackfillStateRepository;
import org.jdkxx.trader.storage.selection.SelectionEarningsRepository;
import org.jdkxx.trader.storage.selection.SelectionPriceBarRepository;
import org.jdkxx.trader.storage.selection.SelectionPriceSeamRepository;
import org.jdkxx.trader.storage.selection.SelectionSecurityRepository;
import org.jdkxx.trader.storage.selection.SelectionValuationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Input backfill for quant selection (ARCHITECTURE §25.6). Every part is idempotent and resumable:
 * instruments already OK / EMPTY / SKIPPED are skipped, FAILED and PENDING (temporarily
 * unavailable) ones are fetched again; {@code force} refetches everything.
 *
 * <p>A failure on one instrument only records its state and does not stop the batch; if the gateway
 * is not connected the whole batch stops (carrying on would just mark every instrument as failed).
 * Rate limiting for Futu requests lives in the gateway, and so does the pacing of IBKR historical
 * data (50 requests per 10 minutes); this class only handles ordering and bookkeeping.
 */
public class SelectionBackfillService {

  private static final Logger log = LoggerFactory.getLogger(SelectionBackfillService.class);
  private static final Set<String> DONE = Set.of("OK", "EMPTY", "SKIPPED");
  private static final List<FinancialStatement> STATEMENTS =
      List.of(
          FinancialStatement.INCOME,
          FinancialStatement.BALANCE_SHEET,
          FinancialStatement.CASH_FLOW);

  /** IBKR historical data service "no data". */
  static final int NO_DATA = 162;

  /**
   * Share-count corporate actions: on the ex-date IBKR (adjusted) and Futu (unadjusted) returns
   * legitimately disagree, so the seam check skips those days.
   */
  private static final long SHARE_CHANGE =
      RehabFactor.ACT_SPLIT
          | RehabFactor.ACT_JOIN
          | RehabFactor.ACT_BONUS
          | RehabFactor.ACT_TRANSFER
          | RehabFactor.ACT_SPIN_OFF;

  private final SelectionProperties props;
  private final SelectionTargets targets;
  private final SelectionBackfillStateRepository states;
  private final PriceHistoryGateway prices;
  private final ReferenceDataGateway contracts;
  private final SelectionDataGateway fundamentals;
  private final InstrumentRepository instruments;
  private final DailyBarRepository dailyBars;
  private final RehabFactorRepository rehabs;
  private final FinancialRepository financials;
  private final SelectionPriceBarRepository priceBars;
  private final SelectionPriceSeamRepository seams;
  private final SelectionValuationRepository valuations;
  private final SelectionEarningsRepository earnings;
  private final SelectionSecurityRepository securities;
  private final Clock clock;
  private final ZoneId zone;

  public SelectionBackfillService(
      SelectionProperties props,
      SelectionTargets targets,
      SelectionBackfillStateRepository states,
      PriceHistoryGateway prices,
      ReferenceDataGateway contracts,
      SelectionDataGateway fundamentals,
      InstrumentRepository instruments,
      DailyBarRepository dailyBars,
      RehabFactorRepository rehabs,
      FinancialRepository financials,
      SelectionPriceBarRepository priceBars,
      SelectionPriceSeamRepository seams,
      SelectionValuationRepository valuations,
      SelectionEarningsRepository earnings,
      SelectionSecurityRepository securities,
      Clock clock,
      ZoneId zone) {
    this.props = props;
    this.targets = targets;
    this.states = states;
    this.prices = prices;
    this.contracts = contracts;
    this.fundamentals = fundamentals;
    this.instruments = instruments;
    this.dailyBars = dailyBars;
    this.rehabs = rehabs;
    this.financials = financials;
    this.priceBars = priceBars;
    this.seams = seams;
    this.valuations = valuations;
    this.earnings = earnings;
    this.securities = securities;
    this.clock = clock;
    this.zone = zone;
  }

  /** Result for one part of one instrument. */
  record Outcome(String status, String detail) {
    static Outcome ok(String detail) {
      return new Outcome("OK", detail);
    }

    static Outcome empty(String detail) {
      return new Outcome("EMPTY", detail);
    }

    static Outcome skipped(String detail) {
      return new Outcome("SKIPPED", detail);
    }

    /**
     * Temporarily unavailable (not a failure): not counted as done, retried on the next backfill.
     */
    static Outcome pending(String detail) {
      return new Outcome("PENDING", detail);
    }
  }

  public String run(BackfillPart part, Collection<String> symbols, boolean force, JobContext ctx) {
    List<InstrumentRow> list = targets.only(symbols);
    Map<Long, String> done = force ? Map.of() : states.statuses(part.name());
    Map<String, Integer> tally = new HashMap<>();
    int skipped = 0;
    int processed = 0;
    for (int i = 0; i < list.size(); i++) {
      if (ctx.cancelled()) {
        ctx.partial("已取消");
        break;
      }
      InstrumentRow row = list.get(i);
      String prior =
          done.get(row.id()); // null if never fetched; Set.of(...).contains(null) would throw NPE
      if (prior != null && DONE.contains(prior)) {
        skipped++;
        continue;
      }
      Outcome o;
      try {
        o =
            switch (part) {
              case PRICES -> prices(row);
              case VALUATION -> valuation(row);
              case EARNINGS -> earnings(row);
              case FINANCIALS -> financials(row);
              case TYPES -> types(row);
            };
      } catch (Exception e) {
        Throwable c = unwrap(e);
        if (c instanceof NotConnectedException) {
          ctx.partial("网关未连接，停在第 " + (i + 1) + " 只（" + row.symbol() + "）：" + c.getMessage());
          break;
        }
        log.warn("量化选股回补 {} {} 失败：{}", part, row.symbol(), c.toString());
        o = new Outcome("FAILED", c.toString());
      }
      states.save(row.id(), part.name(), o.status(), o.detail());
      tally.merge(o.status(), 1, Integer::sum);
      processed++;
      ctx.progress(
          part
              + " "
              + (i + 1)
              + "/"
              + list.size()
              + " "
              + row.symbol()
              + "："
              + o.status()
              + " "
              + tally);
    }
    int failed = tally.getOrDefault("FAILED", 0);
    if (failed > 0) {
      ctx.partial(failed + " 只失败（重跑会只取失败与未取的）");
    }
    return "量化选股回补 "
        + part
        + "：范围 "
        + list.size()
        + " 只，本次处理 "
        + processed
        + "，已完成跳过 "
        + skipped
        + "，结果 "
        + tally;
  }

  // ------------------------------------------------------------------ prices (IBKR)

  /**
   * IBKR split-adjusted daily bars for the years before Futu's history starts, plus the seam check
   * against Futu on their overlap. Only names whose Futu history starts after {@code priceFrom}
   * need this.
   */
  Outcome prices(InstrumentRow row) throws Exception {
    Instrument instrument = row.instrument();
    LocalDate futuFrom =
        dailyBars
            .coverage(row.id())
            .map(DailyBarRepository.InstrumentCoverage::earliest)
            .orElse(null);
    if (futuFrom == null) {
      // 3.2.0 recorded this as EMPTY, which counted as done and was skipped forever (production
      // SKYD, TWLO, VYLR: newly added by the constituent sync, daily bars not fetched yet)
      return Outcome.pending("富途还没有日 K，没有接缝可核对；等日 K 到了下次回补重试");
    }
    if (!futuFrom.isAfter(props.priceFrom())) {
      return Outcome.skipped("富途日 K 自 " + futuFrom + " 起，已早于 " + props.priceFrom() + "，不需要盈透");
    }
    long conId = conId(row);
    LocalDate today = LocalDate.now(clock.withZone(zone));
    LocalDate to = min(futuFrom.plusMonths(props.seamMonths()), today.minusDays(1));
    // Ask where the earliest bar is first: when the whole window lies before the earliest date,
    // IBKR sometimes answers 162 and sometimes never answers at all, leaving us to wait for the
    // timeout (2026-10-17 measured: SKYD earliest 2025-08-07; windows ending 2023-02-17 /
    // 2024-01-01 hung all 6 times, while one ending 2025-08-06 got 162 in 0.5 s; similar windows
    // for CRH and SW all got 162). If the earliest date is not before the Futu start there is
    // nothing to fetch; if it is, fetch from the earliest date and never request the stretch IBKR
    // does not have.
    LocalDate earliest;
    try {
      earliest = prices.earliestDailyBar(conId).get(60, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      if (unwrap(e) instanceof RequestRejectedException r && r.code() == NO_DATA) {
        clearPrices(row.id());
        return Outcome.empty("盈透 conId " + conId + " 没有日线（最早数据时间 162）");
      }
      throw e;
    }
    if (!earliest.isBefore(futuFrom)) {
      clearPrices(row.id());
      return Outcome.skipped("盈透最早 " + earliest + "（最早数据时间），不早于富途起点 " + futuFrom + "，富途已覆盖，不需要盈透");
    }
    LocalDate from = earliest.isAfter(props.priceFrom()) ? earliest : props.priceFrom();
    List<SplitAdjustedBar> bars;
    try {
      bars = prices.splitAdjustedDailyBars(conId, from, to).get(180, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      // 162 "HMDS query returned no data" means the broker explicitly has nothing - not a failure:
      // record EMPTY and do not retry on resume (2026-10-11 measured: ODFL before 2024-05, CRH
      // before 2023-09, SW before its listing all behave this way)
      if (unwrap(e) instanceof RequestRejectedException r && r.code() == NO_DATA) {
        clearPrices(row.id());
        return Outcome.empty("盈透 conId " + conId + " 在 " + from + "~" + to + " 没有日线（162）");
      }
      throw e;
    }
    if (bars.isEmpty()) {
      clearPrices(row.id());
      return Outcome.empty("盈透 conId " + conId + " 在 " + from + "~" + to + " 没有日线");
    }
    LocalDate ibFrom = bars.getFirst().tradeDate();
    LocalDate ibTo = bars.getLast().tradeDate();
    if (!ibFrom.isBefore(futuFrom)) {
      // IBKR has nothing earlier than Futu (a recent listing): the IBKR segment is useless, so do
      // not store it and do not check the seam
      clearPrices(row.id());
      return Outcome.skipped("盈透最早 " + ibFrom + "，不早于富途起点 " + futuFrom + "，富途已覆盖，不需要盈透");
    }
    priceBars.replaceAll(row.id(), bars, today);

    Map<LocalDate, BigDecimal> futuCloses = new HashMap<>();
    for (DailyBar b : dailyBars.find(instrument, row.id(), futuFrom, to)) {
      if (!b.blank() && b.close() != null && b.close().signum() > 0) {
        futuCloses.put(b.tradeDate(), b.close());
      }
    }
    Set<LocalDate> events = new HashSet<>();
    for (RehabFactor f : rehabs.find(instrument, row.id())) {
      if ((f.companyActFlag() & SHARE_CHANGE) != 0) {
        events.add(f.exDate());
      }
    }
    PriceSeam.Result seam = PriceSeam.check(bars, futuCloses, events, props.seamThresholds());
    seams.save(
        row.id(),
        conId,
        ibFrom,
        ibTo,
        futuFrom,
        seam.overlapDays(),
        seam.comparedDays(),
        seam.withinDays(),
        seam.maxAbsDiff(),
        seam.ok(),
        seam.detail());
    String head = "盈透 " + bars.size() + " 根 " + ibFrom + "~" + ibTo + "（富途自 " + futuFrom + "）";
    if (ibFrom.isAfter(props.priceFrom().plusDays(10))) {
      head += "；盈透最早只到 " + ibFrom;
    }
    return Outcome.ok(head + "；接缝" + (seam.ok() ? "通过" : "不通过") + "：" + seam.detail());
  }

  /**
   * When a refetch no longer needs the IBKR segment, clear the previously stored bars and seam
   * verdict so no stale-basis data is left behind.
   */
  private void clearPrices(long instrumentId) {
    priceBars.replaceAll(instrumentId, List.of(), LocalDate.now(clock.withZone(zone)));
    seams.delete(instrumentId);
  }

  /**
   * A conId already bound (written by the holdings sync) wins; otherwise look up the contract and
   * bind it. Only a single USD-denominated candidate is accepted; with several candidates we do not
   * guess.
   */
  long conId(InstrumentRow row) throws Exception {
    var bound = instruments.ibkrConId(row.id());
    if (bound.isPresent()) {
      return bound.get();
    }
    List<InstrumentInfo> found = contracts.lookup(row.instrument()).get(60, TimeUnit.SECONDS);
    List<InstrumentInfo> usd =
        found.stream().filter(i -> i.currency() == null || "USD".equals(i.currency())).toList();
    if (usd.isEmpty()) {
      throw new IllegalStateException("盈透查无此标的（" + row.symbol() + "）");
    }
    if (usd.size() > 1) {
      throw new IllegalStateException(
          "盈透有 "
              + usd.size()
              + " 个候选，不猜："
              + usd.stream()
                  .map(i -> i.brokerRef() + "/" + i.primaryExchange() + "/" + i.name())
                  .toList());
    }
    long conId = Long.parseLong(usd.getFirst().brokerRef());
    if (!instruments.bindIbkrConId(row.id(), conId)) {
      // Someone else bound a different one concurrently: the database wins
      return instruments.ibkrConId(row.id()).orElse(conId);
    }
    return conId;
  }

  // ------------------------------------------------------------------ valuation history (Futu)

  Outcome valuation(InstrumentRow row) throws Exception {
    Map<ValuationMetric, String> spans = new EnumMap<>(ValuationMetric.class);
    int total = 0;
    for (ValuationMetric m : ValuationMetric.values()) {
      List<ValuationPoint> points =
          fundamentals
              .valuationHistory(row.instrument(), m, props.valuationRange())
              .get(90, TimeUnit.SECONDS);
      total += valuations.upsertHistory(row.id(), m, points);
      spans.put(
          m,
          points.isEmpty()
              ? "无"
              : points.size()
                  + " 天 "
                  + points.getFirst().tradeDate()
                  + "~"
                  + points.getLast().tradeDate());
    }
    return total == 0 ? Outcome.empty("三种估值都回空：" + spans) : Outcome.ok(spans.toString());
  }

  // ------------------------------------------------------------------ security type (IBKR)

  /**
   * stockType from IBKR contract details (2026-10-12 measured over 494 names: COMMON 485, ADR 3,
   * REIT 3, NY REG SHRS 1). Only a single USD-denominated candidate is accepted; with several
   * candidates we do not guess. When the broker gives no type a row is still written (type empty)
   * and scoring excludes it as "cannot be determined".
   */
  Outcome types(InstrumentRow row) throws Exception {
    List<InstrumentInfo> found = contracts.lookup(row.instrument()).get(60, TimeUnit.SECONDS);
    List<InstrumentInfo> usd =
        found.stream().filter(i -> i.currency() == null || "USD".equals(i.currency())).toList();
    if (usd.isEmpty()) {
      throw new IllegalStateException("盈透查无此标的（" + row.symbol() + "）");
    }
    if (usd.size() > 1) {
      throw new IllegalStateException(
          "盈透有 "
              + usd.size()
              + " 个候选，不猜："
              + usd.stream()
                  .map(i -> i.brokerRef() + "/" + i.securityType() + "/" + i.name())
                  .toList());
    }
    InstrumentInfo info = usd.getFirst();
    Long conId = info.brokerRef() == null ? null : Long.valueOf(info.brokerRef());
    securities.save(row.id(), info.securityType(), conId);
    return info.securityType() == null
        ? Outcome.empty("盈透没给证券类型（conId " + conId + "）")
        : Outcome.ok(info.securityType() + "（conId " + conId + "）");
  }

  // ------------------------------------------------------------------ announcement dates (Futu)

  Outcome earnings(InstrumentRow row) throws Exception {
    List<EarningsEvent> events =
        fundamentals.earningsHistory(row.instrument()).get(90, TimeUnit.SECONDS);
    if (events.isEmpty()) {
      return Outcome.empty("券商没有公告日");
    }
    earnings.upsertAll(row.id(), events);
    LocalDate first =
        events.stream().map(EarningsEvent::pubDate).min(LocalDate::compareTo).orElseThrow();
    LocalDate last =
        events.stream().map(EarningsEvent::pubDate).max(LocalDate::compareTo).orElseThrow();
    return Outcome.ok(events.size() + " 期，公告日 " + first + "~" + last);
  }

  // ------------------------------------------------------------------ quarterly reports (Futu)

  Outcome financials(InstrumentRow row) throws Exception {
    List<String> parts = new ArrayList<>();
    int total = 0;
    for (FinancialStatement st : STATEMENTS) {
      List<FinancialReport> reports =
          fundamentals
              .quarterlyFinancials(row.instrument(), st, props.financialPeriods())
              .get(90, TimeUnit.SECONDS);
      total += financials.upsertAll(row.id(), reports);
      LocalDate earliest =
          reports.stream()
              .map(FinancialReport::periodEnd)
              .filter(java.util.Objects::nonNull)
              .min(LocalDate::compareTo)
              .orElse(null);
      parts.add(st + " " + reports.size() + " 期" + (earliest == null ? "" : " 自 " + earliest));
    }
    return total == 0 ? Outcome.empty("三类季报都回空：" + parts) : Outcome.ok(String.join("；", parts));
  }

  private static LocalDate min(LocalDate a, LocalDate b) {
    return a.isBefore(b) ? a : b;
  }

  /**
   * Unwraps ExecutionException / CompletionException to the real cause (e.g. NotConnectedException,
   * RequestRejectedException).
   */
  private static Throwable unwrap(Throwable t) {
    Throwable c = t;
    while ((c instanceof ExecutionException || c instanceof CompletionException)
        && c.getCause() != null) {
      c = c.getCause();
    }
    return c;
  }
}
