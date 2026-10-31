package org.jdkxx.trader.core.selection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.domain.selection.SelectionModel;
import org.jdkxx.trader.domain.selection.sim.SimCriteria;
import org.jdkxx.trader.domain.selection.sim.SimLedger;
import org.jdkxx.trader.domain.selection.sim.SimPath;
import org.jdkxx.trader.domain.selection.sim.SimRules;
import org.jdkxx.trader.domain.selection.sim.SimSetup;
import org.jdkxx.trader.domain.signal.SentinelEvaluation;
import org.jdkxx.trader.domain.signal.SignalBar;
import org.jdkxx.trader.domain.signal.SignalInputs;
import org.jdkxx.trader.domain.signal.SignalTrades;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.selection.SelectionScoreRepository;
import org.jdkxx.trader.storage.selection.SelectionSimRepository;
import org.jdkxx.trader.storage.selection.SimTradeRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quant selection - simulated trading (ARCHITECTURE §26): a paper account used to check whether the
 * stock picks are any good. It <b>places no orders and never touches a gateway</b>; it books
 * everything from the daily bars in the database.
 *
 * <ul>
 *   <li>Add: only the top {@code topN} rated names by composite score on the latest score date can
 *       be added. The stop and the resistance zone are fixed on the score date and never change
 *       afterwards. Entry is at the first open after the click; at most one trade per symbol at a
 *       time and at most {@code slots} trades in total (pending entries included);
 *   <li>Daily (after the quant selection daily job): every unfinished trade is <b>recomputed end to
 *       end</b> from the levels set when it was added up to the cutoff date ({@link SimPath}). A
 *       pending trade whose entry day has arrived gets its share count by equal weighting; stop and
 *       chandelier stop close automatically, take-profit only raises an alert; then the daily
 *       equity rows are written;
 *   <li>Manual close: fills at the first open after the click; a trade that has not entered yet is
 *       simply cancelled;
 *   <li>Weekly: a weekly report snapshot is persisted, with SPY and equal-weighted-universe
 *       comparisons over the same period, plus per-trade pairing against the shadow variant.
 * </ul>
 *
 * Add, close and recompute share one lock: while the recompute has read a trade and is about to
 * write it back, a close request must not slip in between and get overwritten.
 */
public class SelectionSimService {

  private static final Logger log = LoggerFactory.getLogger(SelectionSimService.class);

  /**
   * Regular session open (US/Eastern): clicks before it fill at today's open, later clicks at the
   * next trading day's open.
   */
  static final LocalTime REGULAR_OPEN = LocalTime.of(9, 30);

  /** Fixed disclaimers in the weekly report (not statistical conclusions). */
  static final List<String> CAVEATS =
      List.of(
          "不计佣金与滑点；价格只按结构性事件调整、不含普通分红，SPY 与范围等权对照同口径",
          "止损按收盘判定、按收盘成交，跳空跌穿照实记；入场与手动平仓按点击后的第一个开盘价",
          "同一周买入的标的高度相关，样本数 n 远小于笔数所暗示的独立样本数；几周的结果与噪声分不开",
          "只做多：上涨市里绝对盈利首先来自大盘，判断选股看对 SPY 的超额，不看绝对盈亏",
          "本周平仓的影子变体（不手动平、只按止损走）的结果截至生成时（generatedThrough）；影子还没平仓的不参与配对");

  private final SimRules rules = SimRules.V1;
  private final SelectionSimRepository sim;
  private final SelectionScoreRepository scores;
  private final SelectionTargets targets;
  private final InstrumentRepository instruments;
  private final DailyBarRepository bars;
  private final RehabFactorRepository rehabs;
  private final TradingDayRepository days;
  private final Supplier<LocalDate> settledCutoff;
  private final com.fasterxml.jackson.databind.ObjectMapper json;
  private final Clock clock;
  private final ZoneId zone;
  private final ReentrantLock lock = new ReentrantLock();

  public SelectionSimService(
      SelectionSimRepository sim,
      SelectionScoreRepository scores,
      SelectionTargets targets,
      InstrumentRepository instruments,
      DailyBarRepository bars,
      RehabFactorRepository rehabs,
      TradingDayRepository days,
      Supplier<LocalDate> settledCutoff,
      com.fasterxml.jackson.databind.ObjectMapper json,
      Clock clock,
      ZoneId zone) {
    this.sim = sim;
    this.scores = scores;
    this.targets = targets;
    this.instruments = instruments;
    this.bars = bars;
    this.rehabs = rehabs;
    this.days = days;
    this.settledCutoff = settledCutoff;
    this.json = json;
    this.clock = clock;
    this.zone = zone;
  }

  public SimRules rules() {
    return rules;
  }

  // ------------------------------------------------------------------ add and close

  /** The top N on the latest score date and whether each can be added. */
  public record Candidate(
      int rank,
      long instrumentId,
      String symbol,
      String rating,
      BigDecimal composite,
      String state,
      Long tradeId) {}

  public record Candidates(LocalDate scoreDate, int active, int slots, List<Candidate> top) {}

  public Candidates candidates() {
    LocalDate d = scores.latestDate(SelectionModel.VERSION).orElse(null);
    int active = sim.activeCount();
    if (d == null) {
      return new Candidates(null, active, rules.slots(), List.of());
    }
    Map<Long, SimTradeRow> activeBy = new HashMap<>();
    sim.list(null).stream()
        .filter(SimTradeRow::active)
        .forEach(t -> activeBy.put(t.instrumentId(), t));
    List<Candidate> out = new ArrayList<>();
    List<SelectionScoreRepository.ScoreView> top = topOf(d);
    for (int i = 0; i < top.size(); i++) {
      SelectionScoreRepository.ScoreView s = top.get(i);
      SimTradeRow t = activeBy.get(s.instrumentId());
      String state = t != null ? "ACTIVE" : active >= rules.slots() ? "FULL" : "AVAILABLE";
      out.add(
          new Candidate(
              i + 1,
              s.instrumentId(),
              s.symbol(),
              s.rating(),
              s.composite(),
              state,
              t == null ? null : t.id()));
    }
    return new Candidates(d, active, rules.slots(), out);
  }

  /**
   * Preview before adding: the fixed levels, the earliest entry date, and a share count estimated
   * from the score-date close (the real share count is set on the entry day from the cash available
   * then).
   */
  public record Preview(
      String symbol,
      int rank,
      LocalDate scoreDate,
      String rating,
      BigDecimal composite,
      SimSetup setup,
      LocalDate plannedEntryDate,
      double estimatedNotional,
      int estimatedShares,
      double cashNow) {}

  public Preview preview(String symbol) {
    Eligible e = eligible(symbol);
    LocalDate planned = nextOpenDate(Instant.now(clock));
    Cash c = cashNow();
    double notional = Math.min((c.initial() + c.realized()) / rules.slots(), c.cash());
    return new Preview(
        e.row().symbol(),
        e.rank(),
        e.scoreDate(),
        e.score().rating(),
        e.score().composite(),
        e.setup(),
        planned,
        round2(notional),
        SimLedger.shares(c.initial() + c.realized(), rules.slots(), c.cash(), e.setup().close()),
        round2(c.cash()));
  }

  public SimTradeRow add(String symbol) {
    return locked(
        () -> {
          Eligible e = eligible(symbol);
          Instant now = Instant.now(clock);
          LocalDate planned = nextOpenDate(now);
          SimSetup s = e.setup();
          SimTradeRow row =
              SimTradeRow.pending(
                  rules.version(),
                  e.row().id(),
                  SelectionModel.VERSION,
                  e.scoreDate(),
                  e.rank(),
                  e.score().rating(),
                  e.score().composite(),
                  now,
                  d6(s.close()),
                  d6(s.atr()),
                  d6(s.formulaStop()),
                  d6(s.stop()),
                  s.leg().name(),
                  s.resistance() == null ? null : d6(s.resistance()),
                  planned);
          long id = sim.insert(row);
          sim.event(
              id,
              "ADDED",
              e.row().symbol()
                  + " 评分日 "
                  + e.scoreDate()
                  + " 第 "
                  + e.rank()
                  + " 名（"
                  + e.score().rating()
                  + "，合成分 "
                  + e.score().composite()
                  + "）；止损 "
                  + fmt(s.stop())
                  + "（"
                  + legText(s)
                  + "），压力区底 "
                  + (s.resistance() == null ? "无" : fmt(s.resistance()))
                  + "；最早入场日 "
                  + planned
                  + " 开盘");
          return sim.find(id).orElseThrow();
        });
  }

  /**
   * Cancels a trade that has not entered yet; for an open trade records a close request that fills
   * at the first open after the click (TAKE_PROFIT if a take-profit alert has fired, otherwise
   * MANUAL).
   */
  public SimTradeRow requestClose(long id, String note) {
    return locked(
        () -> {
          SimTradeRow t =
              sim.find(id).orElseThrow(() -> new NoSuchElementException("没有这笔模拟交易：#" + id));
          if (!t.active()) {
            throw new IllegalStateException("#" + id + " 已经是 " + t.status() + "，不能再平仓");
          }
          if (t.closeRequestedAt() != null) {
            throw new IllegalStateException(
                "#"
                    + id
                    + " 已在 "
                    + t.closeRequestedAt()
                    + " 请求过平仓，计划 "
                    + t.plannedExitDate()
                    + " 开盘成交");
          }
          Instant now = Instant.now(clock);
          LocalDate exitDate = nextOpenDate(now);
          String clean = note == null || note.isBlank() ? null : note.trim();
          // The entry open has not happened yet: cancel outright. If the entry open has already
          // passed (the job just has not recorded it as held yet), record a normal close request
          // instead.
          if ("PENDING_ENTRY".equals(t.status()) && !exitDate.isAfter(t.plannedEntryDate())) {
            SimTradeRow c = with(t, "CANCELLED", "手动撤销（尚未入场）" + (clean == null ? "" : "：" + clean));
            sim.update(c);
            sim.event(
                id, "CANCELLED", t.symbol() + " 入场前手动撤销" + (clean == null ? "" : "：" + clean));
            return sim.find(id).orElseThrow();
          }
          String kind = t.targetHitDate() != null ? "TAKE_PROFIT" : "MANUAL";
          SimTradeRow r =
              new SimTradeRow(
                  t.id(),
                  t.rulesVersion(),
                  t.instrumentId(),
                  t.symbol(),
                  t.modelVersion(),
                  t.scoreDate(),
                  t.scoreRank(),
                  t.rating(),
                  t.composite(),
                  t.requestedAt(),
                  t.setupClose(),
                  t.setupAtr(),
                  t.formulaStop(),
                  t.stop(),
                  t.stopLeg(),
                  t.resistance(),
                  t.plannedEntryDate(),
                  t.status(),
                  t.entryDate(),
                  t.entryPrice(),
                  t.shares(),
                  t.plusOneR(),
                  t.target(),
                  t.targetSource(),
                  t.touchedPlusOneR(),
                  t.targetHitDate(),
                  t.activeStop(),
                  t.chandelierActive(),
                  now,
                  exitDate,
                  kind,
                  clean,
                  t.exitDate(),
                  t.exitPrice(),
                  t.exitReason(),
                  t.pnl(),
                  t.returnPct(),
                  t.rMultiple(),
                  t.mfeR(),
                  t.maeR(),
                  t.barsHeld(),
                  t.lastClose(),
                  t.lastCloseDate(),
                  t.unrealizedPnl(),
                  t.spyReturn(),
                  t.excessReturn(),
                  t.shadowStatus(),
                  t.shadowExitDate(),
                  t.shadowExitPrice(),
                  t.shadowExitReason(),
                  t.shadowPnl(),
                  t.shadowReturn(),
                  t.shadowLastClose(),
                  t.cancelReason(),
                  t.updatedThrough(),
                  t.updatedAt());
          sim.update(r);
          sim.event(
              id,
              "CLOSE_REQUESTED",
              t.symbol()
                  + ("TAKE_PROFIT".equals(kind) ? " 止盈" : " 手动")
                  + "平仓请求，计划 "
                  + exitDate
                  + " 开盘成交"
                  + (clean == null ? "" : "：" + clean));
          return sim.find(id).orElseThrow();
        });
  }

  private record Eligible(
      InstrumentRow row,
      int rank,
      LocalDate scoreDate,
      SelectionScoreRepository.ScoreView score,
      SimSetup setup) {}

  private Eligible eligible(String symbol) {
    String sym = symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.ROOT);
    LocalDate d =
        scores
            .latestDate(SelectionModel.VERSION)
            .orElseThrow(() -> new IllegalStateException("还没有任何评分结果"));
    List<SelectionScoreRepository.ScoreView> top = topOf(d);
    int rank = -1;
    for (int i = 0; i < top.size(); i++) {
      if (top.get(i).symbol().equalsIgnoreCase(sym)) {
        rank = i + 1;
      }
    }
    if (rank < 0) {
      throw new IllegalArgumentException(sym + " 不在最新评分日 " + d + " 合成分前 " + rules.topN() + " 名里");
    }
    SelectionScoreRepository.ScoreView score = top.get(rank - 1);
    InstrumentRow row = instruments.findById(score.instrumentId()).orElseThrow();
    sim.activeFor(row.id())
        .ifPresent(
            t -> {
              throw new IllegalStateException(sym + " 已在模拟池里（#" + t.id() + "，" + t.status() + "）");
            });
    int active = sim.activeCount();
    if (active >= rules.slots()) {
      throw new IllegalStateException("模拟池已满：进行中 " + active + " 笔（上限 " + rules.slots() + "）");
    }
    return new Eligible(row, rank, d, score, setup(row, d));
  }

  /**
   * Stop and resistance zone on the score date: reuses gate 4 of the entry signal and its exit plan
   * (same data-layer checks and the same structural price basis).
   */
  SimSetup setup(InstrumentRow row, LocalDate scoreDate) {
    LocalDate from = scoreDate.minusDays(rules.sentinel().windowCalendarDays());
    List<DailyBar> raw = bars.find(row.instrument(), row.id(), from, scoreDate);
    List<RehabFactor> factors = rehabs.find(row.instrument(), row.id());
    Set<LocalDate> calendar = new HashSet<>(days.between(Market.US, from, scoreDate));
    SentinelEvaluation e = SignalInputs.judge(raw, factors, calendar, scoreDate, rules.sentinel());
    return SimSetup.from(e, rules);
  }

  private List<SelectionScoreRepository.ScoreView> topOf(LocalDate d) {
    return scores.day(SelectionModel.VERSION, d, null, null).stream()
        .filter(s -> s.rating() != null && s.exclusion() == null)
        .limit(rules.topN())
        .toList();
  }

  /**
   * Date of the first regular-session open after the click: before 09:30 (US/Eastern) on a trading
   * day it is today, otherwise the next trading day. When the calendar does not reach that far into
   * the future, weekends are skipped and the next weekday is returned (holidays cannot be
   * recognised; entry then takes the first bar on or after that date, so the result is still
   * correct).
   */
  LocalDate nextOpenDate(Instant at) {
    ZonedDateTime now = at.atZone(zone);
    LocalDate today = now.toLocalDate();
    LocalDate candidate = now.toLocalTime().isBefore(REGULAR_OPEN) ? today : today.plusDays(1);
    List<LocalDate> ahead = days.between(Market.US, candidate, candidate.plusDays(14));
    if (!ahead.isEmpty()) {
      return ahead.getFirst();
    }
    while (candidate.getDayOfWeek().getValue() >= 6) {
      candidate = candidate.plusDays(1);
    }
    return candidate;
  }

  // ------------------------------------------------------------------ daily recompute

  /**
   * True when some trade needs computing but has not been computed through {@code through}: used by
   * the daily job's 22:30 catch-up (same condition as the audit check simUpdated, see {@link
   * #due}).
   */
  public boolean stale(LocalDate through) {
    return sim.unfinished().stream()
        .filter(t -> due(t, through))
        .anyMatch(t -> t.updatedThrough() == null || t.updatedThrough().isBefore(through));
  }

  /**
   * Whether this trade has anything to compute as of d: every held trade does (including closed
   * trades whose shadow is still open); a pending trade only when its entry date is not after d. A
   * pending trade whose entry date is after d has no bars to compute yet, so "not computed through
   * d" is normal. 3.4.1 lacked this rule: 10 trades added after the 18:30 job (entry date the next
   * day) made the critical audit check simUpdated fail falsely, and the 22:30 catch-up would also
   * rerun the whole daily job for them (2026-10-12, production).
   */
  static boolean due(SimTradeRow t, LocalDate d) {
    return !"PENDING_ENTRY".equals(t.status()) || !t.plannedEntryDate().isAfter(d);
  }

  /**
   * Computes every unfinished trade through {@code through}, then fills in the daily equity rows.
   * <b>Forward only</b>: trades already computed to a later date are skipped (re-running an old
   * date never rolls state back). Held trades are recomputed first (their closes free up cash),
   * then pending trades get their share counts in order of entry date and request time.
   */
  public String update(LocalDate through, JobContext ctx) {
    return locked(() -> doUpdate(through, ctx));
  }

  private String doUpdate(LocalDate through, JobContext ctx) {
    double initial = sim.account().initialCapital().doubleValue();
    // Cash is never stored: it is derived from every fill (SimLedger). Seed the fill set with what
    // is in the database, then overwrite each trade's entry as it is recomputed below, so later
    // pending entries see the freed-up cash.
    List<SimTradeRow> todo =
        sim.unfinished().stream()
            .filter(t -> t.updatedThrough() == null || t.updatedThrough().isBefore(through))
            .filter(t -> !t.scoreDate().isAfter(through))
            .toList();
    Map<Long, SimLedger.Fill> fills = new LinkedHashMap<>();
    sim.filled().forEach(t -> fills.put(t.id(), fill(t)));
    Spy spy = spy(through);
    Map<Long, NavigableMap<LocalDate, Double>> closes = new HashMap<>();
    int filled = 0;
    int closed = 0;
    int cancelled = 0;
    int failed = 0;
    // Held trades first, pending ones last: a pending trade's share count depends on the cash
    // released by closes that happen at or before its entry open.
    List<SimTradeRow> ordered =
        new ArrayList<>(todo.stream().filter(t -> !"PENDING_ENTRY".equals(t.status())).toList());
    ordered.addAll(todo.stream().filter(t -> "PENDING_ENTRY".equals(t.status())).toList());
    for (SimTradeRow t : ordered) {
      ctx.progress("模拟交易重算 " + t.symbol());
      try {
        List<SignalBar> series = series(t, through);
        SimPath.Path actual =
            SimPath.walk(
                series,
                t.scoreDate(),
                t.plannedEntryDate(),
                t.stop().doubleValue(),
                t.resistance() == null ? null : t.resistance().doubleValue(),
                t.plannedExitDate(),
                rules);
        SimPath.Path shadow =
            t.plannedExitDate() == null
                ? actual
                : SimPath.walk(
                    series,
                    t.scoreDate(),
                    t.plannedEntryDate(),
                    t.stop().doubleValue(),
                    t.resistance() == null ? null : t.resistance().doubleValue(),
                    null,
                    rules);
        Integer shares = t.shares();
        if ("PENDING_ENTRY".equals(t.status())) {
          if (actual.state() == SimPath.State.PENDING) {
            sim.update(through(t, through));
            continue;
          }
          if (actual.state() == SimPath.State.CANCELLED) {
            sim.update(through(with(t, "CANCELLED", actual.cancelReason()), through));
            sim.event(
                t.id(),
                "CANCELLED",
                t.symbol() + " " + actual.entryDate() + " " + actual.cancelReason());
            cancelled++;
            continue;
          }
          LocalDate e = actual.entryDate();
          // Equal weight: notional = min((initial + realized before this open) / slots, cash
          // available at this open).
          // Same-day ordering: opens close first (manual / take-profit), then entries by request
          // time.
          double capital = initial + SimLedger.realizedBeforeOpen(fills.values(), e);
          double cash = SimLedger.cashAtOpen(fills.values(), e, t.requestedAt(), initial);
          shares = SimLedger.shares(capital, rules.slots(), cash, actual.entry());
          if (shares <= 0) {
            String why = e + " 开盘可用现金 " + fmt2(cash) + " 不够买 1 股（" + fmt(actual.entry()) + "）";
            sim.update(through(with(t, "CANCELLED", why), through));
            sim.event(t.id(), "CANCELLED", t.symbol() + " " + why);
            cancelled++;
            continue;
          }
          sim.event(
              t.id(),
              "FILLED",
              t.symbol()
                  + " "
                  + e
                  + " 开盘 "
                  + fmt(actual.entry())
                  + " 买入 "
                  + shares
                  + " 股（名义 "
                  + fmt2(shares * actual.entry())
                  + "；等权额度 "
                  + fmt2(capital / rules.slots())
                  + "、可用现金 "
                  + fmt2(cash)
                  + "）");
          filled++;
        } else if (actual.state() == SimPath.State.PENDING
            || actual.state() == SimPath.State.CANCELLED) {
          // A trade already entered cannot become "not entered" unless its bars were deleted or
          // corrected; building a row from that path used to fail with a NullPointerException
          // (found in the 2026-10-31 review). Fail loudly and leave the row as it is.
          throw new IllegalStateException(
              "已于 "
                  + t.entryDate()
                  + " 入场，重算后变成"
                  + (actual.state() == SimPath.State.PENDING
                      ? "未入场：计划入场日 " + t.plannedEntryDate() + " 起没有 K 线"
                      : "不入场：" + actual.cancelReason())
                  + "（K 线被删或订正？需人工核对）");
        }
        SimTradeRow next = build(t, actual, shadow, shares, spy, through);
        if (next.targetHitDate() != null && t.targetHitDate() == null) {
          sim.event(
              t.id(),
              "TARGET_HIT",
              t.symbol()
                  + " "
                  + next.targetHitDate()
                  + " 盘中触及止盈目标 "
                  + fmt(next.target().doubleValue())
                  + "（"
                  + ("RESISTANCE".equals(next.targetSource())
                      ? "压力区底"
                      : "+" + rules.targetFallbackR() + "R")
                  + "），可手动平仓");
        }
        if ("CLOSED".equals(next.status()) && !"CLOSED".equals(t.status())) {
          sim.event(
              t.id(),
              "CLOSED",
              t.symbol()
                  + " "
                  + next.exitDate()
                  + " "
                  + reasonText(next.exitReason())
                  + " "
                  + fmt(next.exitPrice().doubleValue())
                  + "，盈亏 "
                  + next.pnl()
                  + "（"
                  + pct(next.returnPct())
                  + "，"
                  + next.rMultiple().setScale(2, RoundingMode.HALF_UP)
                  + "R）");
          closed++;
        }
        sim.update(next);
        fills.put(t.id(), fill(next));
        closes.put(t.id(), actual.closes());
      } catch (RuntimeException ex) {
        failed++;
        log.warn("模拟交易 #{} {} 重算失败：{}", t.id(), t.symbol(), ex.toString());
        ctx.partial("#" + t.id() + " " + t.symbol() + " 重算失败：" + ex.getMessage());
      }
    }
    int equityDays = writeEquity(through, fills, closes, initial);
    return "模拟交易算到 "
        + through
        + "：重算 "
        + todo.size()
        + " 笔，新入场 "
        + filled
        + "、平仓 "
        + closed
        + "、取消 "
        + cancelled
        + (failed > 0 ? "、失败 " + failed : "")
        + "；逐日权益写 "
        + equityDays
        + " 天";
  }

  /**
   * Daily bars on the score-date price basis. The fetch window always starts 600 calendar days
   * before the score date (Wilder ATR depends on its starting point); bars are structurally
   * adjusted as of the cutoff and then scaled back to the score-date price level, so stops, entry
   * and exits stay comparable across splits during the holding period.
   */
  private List<SignalBar> series(SimTradeRow t, LocalDate through) {
    InstrumentRow row = instruments.findById(t.instrumentId()).orElseThrow();
    LocalDate from = t.scoreDate().minusDays(rules.sentinel().windowCalendarDays());
    List<DailyBar> raw = bars.find(row.instrument(), row.id(), from, through);
    List<RehabFactor> factors = rehabs.find(row.instrument(), row.id());
    Set<LocalDate> calendar = new HashSet<>(days.between(Market.US, from, through));
    return SignalTrades.scaledTo(raw, factors, calendar, through, t.scoreDate());
  }

  /**
   * Builds the row for a filled trade from the actual path and the shadow path (exit only by stop,
   * never by request). R = (exit - entry) / (entry - stop): entry &gt; stop is guaranteed because
   * {@link SimPath#walk} cancels the trade when the entry open is not above the stop. Prices and
   * shares are both on the score-date basis, so P&amp;L is unaffected by splits.
   */
  private SimTradeRow build(
      SimTradeRow t, SimPath.Path a, SimPath.Path s, int shares, Spy spy, LocalDate through) {
    double entry = a.entry();
    double stop = t.stop().doubleValue();
    double risk = entry - stop;
    boolean closed = a.state() == SimPath.State.CLOSED;
    String reason =
        !closed
            ? null
            : a.reason() == SimPath.ExitReason.REQUESTED ? t.closeKind() : a.reason().name();
    Double pnl = closed ? shares * (a.exit() - entry) : null;
    Double ret = closed ? a.exit() / entry - 1 : null;
    Double unreal = closed ? null : shares * (a.lastClose() - entry);
    Double spyRet =
        closed
            ? spy.ret(a.entryDate(), a.exitDate(), a.reason() == SimPath.ExitReason.REQUESTED)
            : spy.ret(a.entryDate(), a.lastCloseDate(), false);
    double heldRet = closed ? ret : a.lastClose() / entry - 1;
    boolean shadowClosed = s.state() == SimPath.State.CLOSED;
    return new SimTradeRow(
        t.id(),
        t.rulesVersion(),
        t.instrumentId(),
        t.symbol(),
        t.modelVersion(),
        t.scoreDate(),
        t.scoreRank(),
        t.rating(),
        t.composite(),
        t.requestedAt(),
        t.setupClose(),
        t.setupAtr(),
        t.formulaStop(),
        t.stop(),
        t.stopLeg(),
        t.resistance(),
        t.plannedEntryDate(),
        closed ? "CLOSED" : "OPEN",
        a.entryDate(),
        d6(entry),
        shares,
        d6(a.plusOneR()),
        d6(a.target()),
        a.targetSource().name(),
        a.touchedPlusOneR(),
        a.targetHitDate(),
        d6(a.activeStop()),
        a.chandelierActive(),
        t.closeRequestedAt(),
        t.plannedExitDate(),
        t.closeKind(),
        t.closeNote(),
        closed ? a.exitDate() : null,
        closed ? d6(a.exit()) : null,
        reason,
        pnl == null ? null : d2(pnl),
        ret == null ? null : d6(ret),
        closed ? d6((a.exit() - entry) / risk) : null,
        d6((a.highest() - entry) / risk),
        d6((a.lowest() - entry) / risk),
        a.barsHeld(),
        d6(a.lastClose()),
        a.lastCloseDate(),
        unreal == null ? null : d2(unreal),
        spyRet == null ? null : d6(spyRet),
        spyRet == null ? null : d6(heldRet - spyRet),
        shadowClosed ? "CLOSED" : "OPEN",
        shadowClosed ? s.exitDate() : null,
        shadowClosed ? d6(s.exit()) : null,
        shadowClosed ? s.reason().name() : null,
        shadowClosed ? d2(shares * (s.exit() - entry)) : null,
        shadowClosed ? d6(s.exit() / entry - 1) : null,
        d6(s.lastClose()),
        null,
        through,
        t.updatedAt());
  }

  /**
   * Fills in daily equity: from the day after the last row written (or the first entry date if
   * none) up to through.
   */
  private int writeEquity(
      LocalDate through,
      Map<Long, SimLedger.Fill> fills,
      Map<Long, NavigableMap<LocalDate, Double>> closes,
      double initial) {
    if (fills.isEmpty()) {
      return 0;
    }
    LocalDate first =
        fills.values().stream()
            .map(SimLedger.Fill::entryDate)
            .min(LocalDate::compareTo)
            .orElseThrow();
    List<SelectionSimRepository.EquityRow> existing = sim.equity(first, through);
    // Trades recomputed today may change equity anywhere inside their holding period: rewrite from
    // their earliest entry date. The days not written yet are always filled in as well: taking only
    // the recomputed entry date left a permanent gap between the last row and a later entry (found
    // in the 2026-10-31 review)
    LocalDate resume = existing.isEmpty() ? first : existing.getLast().tradeDate().plusDays(1);
    LocalDate from =
        closes.keySet().stream()
            .map(fills::get)
            .map(SimLedger.Fill::entryDate)
            .filter(d -> d.isBefore(resume))
            .min(LocalDate::compareTo)
            .orElse(resume);
    if (from.isBefore(first)) {
      from = first;
    }
    List<LocalDate> span = days.between(Market.US, from, through);
    if (span.isEmpty()) {
      return 0;
    }
    // Closed trades not recomputed this time (holding period overlapping the range) also need their
    // daily closes
    for (SimLedger.Fill f : fills.values()) {
      boolean overlaps =
          !f.entryDate().isAfter(through) && (f.exitDate() == null || !f.exitDate().isBefore(from));
      if (overlaps && !closes.containsKey(f.id())) {
        sim.find(f.id()).ifPresent(t -> closes.put(f.id(), closesOf(t, through)));
      }
    }
    List<SelectionSimRepository.EquityRow> rows = new ArrayList<>();
    for (LocalDate d : span) {
      SimLedger.Equity e = SimLedger.equityAtClose(fills.values(), d, initial, closes);
      rows.add(
          new SelectionSimRepository.EquityRow(
              d, d2(e.cash()), d2(e.positions()), d2(e.total()), e.open()));
    }
    sim.upsertEquity(rows);
    return rows.size();
  }

  private NavigableMap<LocalDate, Double> closesOf(SimTradeRow t, LocalDate through) {
    LocalDate end = t.exitDate() != null && t.exitDate().isBefore(through) ? t.exitDate() : through;
    return SimPath.walk(
            series(t, end),
            t.scoreDate(),
            t.plannedEntryDate(),
            t.stop().doubleValue(),
            t.resistance() == null ? null : t.resistance().doubleValue(),
            t.plannedExitDate(),
            rules)
        .closes();
  }

  private static SimLedger.Fill fill(SimTradeRow t) {
    return new SimLedger.Fill(
        t.id(),
        t.requestedAt(),
        t.entryDate(),
        t.entryPrice().doubleValue(),
        t.shares(),
        t.exitDate(),
        t.exitPrice() == null ? null : t.exitPrice().doubleValue(),
        "TAKE_PROFIT".equals(t.exitReason()) || "MANUAL".equals(t.exitReason()));
  }

  // ------------------------------------------------------------------ SPY comparison

  /**
   * SPY daily bars (structural basis, anchored at the cutoff): a holding-period return is a ratio,
   * so the scale does not matter.
   */
  record Spy(NavigableMap<LocalDate, SignalBar> bars) {

    /**
     * From the entry-day open to the exit price at the same point in time (open or close); a
     * missing bar falls back to the nearest earlier one; null when there is no data.
     */
    Double ret(LocalDate entryDate, LocalDate exitDate, boolean exitAtOpen) {
      Map.Entry<LocalDate, SignalBar> in = bars.floorEntry(entryDate);
      Map.Entry<LocalDate, SignalBar> out = bars.floorEntry(exitDate);
      if (in == null || out == null || !in.getKey().equals(entryDate)) {
        return null;
      }
      double exit =
          exitAtOpen && out.getKey().equals(exitDate)
              ? out.getValue().open()
              : out.getValue().close();
      return exit / in.getValue().open() - 1;
    }

    Double closeToClose(LocalDate from, LocalDate to) {
      Map.Entry<LocalDate, SignalBar> a = bars.floorEntry(from);
      Map.Entry<LocalDate, SignalBar> b = bars.floorEntry(to);
      return a == null || b == null ? null : b.getValue().close() / a.getValue().close() - 1;
    }
  }

  private Spy spy(LocalDate through) {
    Optional<InstrumentRow> row = instruments.find(Instrument.us("SPY"));
    if (row.isEmpty()) {
      return new Spy(new TreeMap<>());
    }
    LocalDate from =
        sim.list(null).stream()
            .map(SimTradeRow::scoreDate)
            .min(LocalDate::compareTo)
            .orElse(through)
            .minusDays(10);
    List<DailyBar> raw = bars.find(row.get().instrument(), row.get().id(), from, through);
    Set<LocalDate> calendar = new HashSet<>(days.between(Market.US, from, through));
    NavigableMap<LocalDate, SignalBar> m = new TreeMap<>();
    if (!raw.isEmpty()) {
      SignalTrades.scaledTo(
              raw, rehabs.find(row.get().instrument(), row.get().id()), calendar, through, through)
          .forEach(b -> m.put(b.date(), b));
    }
    return new Spy(m);
  }

  // ------------------------------------------------------------------ queries

  private record Cash(double initial, double cash, double realized, double cost) {}

  /**
   * Current cash, realized P&amp;L and open cost, rebuilt from all fills (cash is never stored, so
   * any recomputed trade is reflected automatically).
   */
  private Cash cashNow() {
    double initial = sim.account().initialCapital().doubleValue();
    double cash = initial;
    double realized = 0;
    double cost = 0;
    for (SimTradeRow t : sim.filled()) {
      double c = t.shares() * t.entryPrice().doubleValue();
      cash -= c;
      if (t.exitPrice() != null) {
        cash += t.shares() * t.exitPrice().doubleValue();
        realized += t.pnl() == null ? 0 : t.pnl().doubleValue();
      } else {
        cost += c;
      }
    }
    return new Cash(initial, cash, realized, cost);
  }

  /**
   * Account overview: cash, positions (at each trade's latest close), realized and unrealized
   * P&amp;L, and the return since the first entry next to SPY over the same period.
   */
  public record Overview(
      Map<String, Object> rules,
      SelectionSimRepository.Account account,
      LocalDate startDate,
      double cash,
      double positionsValue,
      double equity,
      double realized,
      double unrealized,
      Double totalReturn,
      Double spySinceStart,
      LocalDate markedThrough,
      int open,
      int pending,
      int closed,
      int cancelled,
      int targetAlerts,
      Candidates candidates,
      SimCriteria criteria) {}

  public Overview overview() {
    Cash c = cashNow();
    List<SimTradeRow> all = sim.list(null);
    double positions = 0;
    double unrealized = 0;
    LocalDate marked = null;
    for (SimTradeRow t : all) {
      if ("OPEN".equals(t.status()) && t.lastClose() != null) {
        positions += t.shares() * t.lastClose().doubleValue();
        unrealized += t.unrealizedPnl() == null ? 0 : t.unrealizedPnl().doubleValue();
        marked = marked == null || t.lastCloseDate().isBefore(marked) ? t.lastCloseDate() : marked;
      }
    }
    LocalDate start =
        all.stream()
            .map(SimTradeRow::entryDate)
            .filter(java.util.Objects::nonNull)
            .min(LocalDate::compareTo)
            .orElse(null);
    double equity = c.cash() + positions;
    Double spy = null;
    if (start != null) {
      LocalDate end = settledCutoff.get();
      Spy s = spy(end);
      Map.Entry<LocalDate, SignalBar> in = s.bars().floorEntry(start);
      Map.Entry<LocalDate, SignalBar> out = s.bars().floorEntry(end);
      spy =
          in == null || out == null || !in.getKey().equals(start)
              ? null
              : out.getValue().close() / in.getValue().open() - 1;
    }
    return new Overview(
        rules.toMap(),
        sim.account(),
        start,
        round2(c.cash()),
        round2(positions),
        round2(equity),
        round2(c.realized()),
        round2(unrealized),
        start == null ? null : equity / c.initial() - 1,
        spy,
        marked,
        count(all, "OPEN"),
        count(all, "PENDING_ENTRY"),
        count(all, "CLOSED"),
        count(all, "CANCELLED"),
        (int)
            all.stream()
                .filter(
                    t ->
                        "OPEN".equals(t.status())
                            && t.targetHitDate() != null
                            && t.closeRequestedAt() == null)
                .count(),
        candidates(),
        criteria(start, c.initial(), settledCutoff.get()));
  }

  /**
   * The "consistently profitable" criteria (§26.8), computed from the latest close in the daily
   * equity table: annualized return, versus SPY (first entry-day open to the same closing date),
   * and whether 12 months have elapsed. With no equity rows yet (just entered, the job has not
   * written any) only the day count is given and the verdict is "observing".
   */
  SimCriteria criteria(LocalDate start, double initial, LocalDate through) {
    if (start == null) {
      return SimCriteria.evaluate(null, null, initial, null, null);
    }
    List<SelectionSimRepository.EquityRow> rows = sim.equity(start, through);
    if (rows.isEmpty()) {
      return SimCriteria.evaluate(start, start, initial, null, null);
    }
    SelectionSimRepository.EquityRow last = rows.getLast();
    Double spy = spy(last.tradeDate()).ret(start, last.tradeDate(), false);
    return SimCriteria.evaluate(start, last.tradeDate(), initial, last.equity().doubleValue(), spy);
  }

  public List<SimTradeRow> trades(String status) {
    return sim.list(status == null ? null : status.trim().toUpperCase(java.util.Locale.ROOT));
  }

  public record TradeDetail(SimTradeRow trade, List<SelectionSimRepository.Event> events) {}

  public TradeDetail trade(long id) {
    return new TradeDetail(
        sim.find(id).orElseThrow(() -> new NoSuchElementException("没有这笔模拟交易：#" + id)),
        sim.events(id, 200));
  }

  public List<SelectionSimRepository.Event> events(int limit) {
    return sim.events(null, Math.clamp(limit, 1, 1000));
  }

  public List<SelectionSimRepository.EquityRow> equity(LocalDate from, LocalDate to) {
    return sim.equity(from, to);
  }

  // ------------------------------------------------------------------ weekly report

  /**
   * Body of one week's report (persisted as jsonb, immutable once generated). All ratios are
   * fractions (0.012 = 1.2%).
   */
  public record WeeklyReport(
      String rulesVersion,
      LocalDate weekStart,
      LocalDate weekEnd,
      LocalDate accountStart,
      double initialCapital,
      double equityStart,
      double equityEnd,
      double cashEnd,
      double weekPnl,
      Double weekReturn,
      Double cumulativeReturn,
      double realizedWeek,
      double unrealizedEnd,
      int openAtEnd,
      Double spyWeekReturn,
      Double spySinceStart,
      Double universeWeekReturn,
      int universeCount,
      Double maxDrawdown,
      List<WeeklyTrade> closedThisWeek,
      Totals allClosed,
      List<WeeklyOpen> openPositions,
      LocalDate generatedThrough,
      List<String> caveats,
      SimCriteria criteria) {}

  /**
   * A trade still held at week end, valued <b>at the week-end close</b> (not the latest state at
   * generation time: regenerating an old weekly report must not pull in later closes or prices).
   *
   * @param targetHitDate date the take-profit target was touched on or before week end; null if
   *     never
   */
  public record WeeklyOpen(
      long id,
      String symbol,
      LocalDate entryDate,
      double entry,
      int shares,
      LocalDate markDate,
      Double mark,
      Double unrealizedPnl,
      Double returnPct,
      Double spyReturn,
      Double excessReturn,
      LocalDate targetHitDate) {}

  /**
   * Per trade: the actual result next to the shadow variant (exit by stop only, no manual close),
   * paired to show what the manual take-profit / close gained or lost.
   */
  public record WeeklyTrade(
      long id,
      String symbol,
      LocalDate entryDate,
      double entry,
      int shares,
      LocalDate exitDate,
      Double exit,
      String reason,
      Double pnl,
      Double returnPct,
      Double rMultiple,
      Double spyReturn,
      Double excessReturn,
      String shadowStatus,
      Double shadowPnl,
      Double shadowReturn) {}

  /**
   * All trades closed since inception: count, wins and losses, mean return and mean excess return
   * (n is reported as is, no significance test).
   */
  public record Totals(
      int n,
      int wins,
      int losses,
      double pnl,
      Double meanReturn,
      Double meanExcess,
      int withExcess,
      int manualClosed,
      Double manualMinusShadow,
      int manualPaired) {}

  public List<SelectionSimRepository.WeeklyRow> weeklies(int limit) {
    return sim.weeklies(Math.clamp(limit, 1, 520));
  }

  public SelectionSimRepository.WeeklyRow weeklyRow(LocalDate weekEnd) {
    return sim.weekly(weekEnd).orElseThrow(() -> new NoSuchElementException(weekEnd + " 这一周还没有周报"));
  }

  /**
   * Generates the report for one week (weekEnd null = the week of the latest settled trading day).
   * An existing report is not overwritten unless force (which is logged as an event).
   */
  public String weekly(LocalDate weekEnd, boolean force, JobContext ctx) {
    return locked(
        () -> {
          LocalDate cutoff = settledCutoff.get();
          LocalDate anchor = weekEnd == null ? cutoff : weekEnd;
          LocalDate monday = anchor.minusDays(anchor.getDayOfWeek().getValue() - 1);
          List<LocalDate> week = days.between(Market.US, monday, monday.plusDays(4));
          if (week.isEmpty()) {
            return monday + " 这一周没有交易日，不出周报";
          }
          LocalDate end = week.getLast();
          if (end.isAfter(cutoff)) {
            throw new IllegalArgumentException(
                monday + " 这一周还没结束（最后一个交易日 " + end + "，当前收盘落定到 " + cutoff + "）");
          }
          if (sim.weekly(end).isPresent() && !force) {
            return end + " 的周报已存在，未覆盖（要重新生成用 force=true）";
          }
          doUpdate(cutoff, ctx);
          WeeklyReport r = buildWeekly(week.getFirst(), end);
          if (r == null) {
            return end + " 之前还没有任何入场，不出周报";
          }
          try {
            sim.saveWeekly(
                new SelectionSimRepository.WeeklyRow(
                    end,
                    week.getFirst(),
                    rules.version(),
                    d2(r.equityEnd()),
                    r.weekReturn() == null ? null : d6(r.weekReturn()),
                    r.spyWeekReturn() == null ? null : d6(r.spyWeekReturn()),
                    r.closedThisWeek().size(),
                    json.writeValueAsString(r),
                    null));
          } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
          }
          sim.event(
              null,
              force ? "WEEKLY_REGENERATED" : "WEEKLY_REPORT",
              end
                  + " 周报：权益 "
                  + fmt2(r.equityEnd())
                  + "，本周 "
                  + pct(r.weekReturn())
                  + "（SPY "
                  + pct(r.spyWeekReturn())
                  + "），本周平仓 "
                  + r.closedThisWeek().size()
                  + " 笔");
          return end
              + " 周报已生成：本周 "
              + pct(r.weekReturn())
              + "，SPY "
              + pct(r.spyWeekReturn())
              + "，本周平仓 "
              + r.closedThisWeek().size()
              + " 笔";
        });
  }

  WeeklyReport buildWeekly(LocalDate start, LocalDate end) {
    double initial = sim.account().initialCapital().doubleValue();
    List<SimTradeRow> filled = sim.filled();
    LocalDate accountStart =
        filled.stream().map(SimTradeRow::entryDate).min(LocalDate::compareTo).orElse(null);
    if (accountStart == null || accountStart.isAfter(end)) {
      return null;
    }
    List<SelectionSimRepository.EquityRow> curve = sim.equity(accountStart, end);
    if (curve.isEmpty() || !curve.getLast().tradeDate().equals(end)) {
      throw new IllegalStateException(end + " 的逐日权益还没有（先跑重算）");
    }
    SelectionSimRepository.EquityRow last = curve.getLast();
    double equityStart =
        curve.stream()
            .filter(e -> e.tradeDate().isBefore(start))
            .reduce((a, b) -> b)
            .map(e -> e.equity().doubleValue())
            .orElse(initial);
    double equityEnd = last.equity().doubleValue();

    List<WeeklyTrade> closedWeek = new ArrayList<>();
    List<SimTradeRow> openRows = new ArrayList<>();
    List<SimTradeRow> closedAll = new ArrayList<>();
    double realizedWeek = 0;
    double costOpen = 0;
    for (SimTradeRow t : filled) {
      if (t.exitDate() != null && !t.exitDate().isAfter(end)) {
        closedAll.add(t);
        if (!t.exitDate().isBefore(start)) {
          closedWeek.add(weeklyTrade(t));
          realizedWeek += t.pnl().doubleValue();
        }
      } else if (!t.entryDate().isAfter(end)) {
        openRows.add(t);
        costOpen += t.shares() * t.entryPrice().doubleValue();
      }
    }
    Spy spy = spy(end);
    List<WeeklyOpen> open = new ArrayList<>();
    for (SimTradeRow t : openRows) {
      Map.Entry<LocalDate, Double> m = closesOf(t, end).floorEntry(end);
      double entry = t.entryPrice().doubleValue();
      Double mark = m == null ? null : m.getValue();
      Double ret = mark == null ? null : mark / entry - 1;
      Double spyRet = m == null ? null : spy.ret(t.entryDate(), m.getKey(), false);
      open.add(
          new WeeklyOpen(
              t.id(),
              t.symbol(),
              t.entryDate(),
              entry,
              t.shares(),
              m == null ? null : m.getKey(),
              mark,
              mark == null ? null : round2(t.shares() * (mark - entry)),
              ret,
              spyRet,
              ret == null || spyRet == null ? null : ret - spyRet,
              t.targetHitDate() != null && !t.targetHitDate().isAfter(end)
                  ? t.targetHitDate()
                  : null));
    }
    LocalDate prevEnd =
        days.between(Market.US, start.minusDays(10), start.minusDays(1)).stream()
            .reduce((a, b) -> b)
            .orElse(null);
    Double spyWeek = prevEnd == null ? null : spy.closeToClose(prevEnd, end);
    Map.Entry<LocalDate, SignalBar> in = spy.bars().floorEntry(accountStart);
    Double spySince =
        in == null || !in.getKey().equals(accountStart) || spy.bars().floorEntry(end) == null
            ? null
            : spy.bars().floorEntry(end).getValue().close() / in.getValue().open() - 1;
    double[] universe =
        prevEnd == null ? new double[] {Double.NaN, 0} : universeReturn(prevEnd, end);
    Totals totals = totals(closedAll);
    return new WeeklyReport(
        rules.version(),
        start,
        end,
        accountStart,
        initial,
        round2(equityStart),
        round2(equityEnd),
        last.cash().doubleValue(),
        round2(equityEnd - equityStart),
        equityStart > 0 ? equityEnd / equityStart - 1 : null,
        equityEnd / initial - 1,
        round2(realizedWeek),
        round2(last.positionsValue().doubleValue() - costOpen),
        open.size(),
        spyWeek,
        spySince,
        Double.isNaN(universe[0]) ? null : universe[0],
        (int) universe[1],
        SimLedger.maxDrawdown(curve.stream().map(e -> e.equity().doubleValue()).toList()),
        closedWeek,
        totals,
        open,
        filled.stream()
            .map(SimTradeRow::updatedThrough)
            .filter(java.util.Objects::nonNull)
            .max(LocalDate::compareTo)
            .orElse(end),
        CAVEATS,
        SimCriteria.evaluate(accountStart, end, initial, equityEnd, spySince));
  }

  private static WeeklyTrade weeklyTrade(SimTradeRow t) {
    return new WeeklyTrade(
        t.id(),
        t.symbol(),
        t.entryDate(),
        t.entryPrice().doubleValue(),
        t.shares(),
        t.exitDate(),
        dbl(t.exitPrice()),
        t.exitReason(),
        dbl(t.pnl()),
        dbl(t.returnPct()),
        dbl(t.rMultiple()),
        dbl(t.spyReturn()),
        dbl(t.excessReturn()),
        t.shadowStatus(),
        dbl(t.shadowPnl()),
        dbl(t.shadowReturn()));
  }

  static Totals totals(List<SimTradeRow> closed) {
    int wins = 0;
    double pnl = 0;
    double sumRet = 0;
    double sumExcess = 0;
    int withExcess = 0;
    int manual = 0;
    double manualDiff = 0;
    int paired = 0;
    for (SimTradeRow t : closed) {
      double p = t.pnl().doubleValue();
      pnl += p;
      wins += p > 0 ? 1 : 0;
      sumRet += t.returnPct().doubleValue();
      if (t.excessReturn() != null) {
        sumExcess += t.excessReturn().doubleValue();
        withExcess++;
      }
      if ("TAKE_PROFIT".equals(t.exitReason()) || "MANUAL".equals(t.exitReason())) {
        manual++;
        // Do not pair while the shadow is still open: its outcome is not settled, and comparing
        // against a floating value would treat an unsettled result as settled
        if ("CLOSED".equals(t.shadowStatus()) && t.shadowPnl() != null) {
          manualDiff += p - t.shadowPnl().doubleValue();
          paired++;
        }
      }
    }
    int n = closed.size();
    return new Totals(
        n,
        wins,
        n - wins,
        round2(pnl),
        n == 0 ? null : sumRet / n,
        withExcess == 0 ? null : sumExcess / withExcess,
        withExcess,
        manual,
        paired == 0 ? null : round2(manualDiff),
        paired);
  }

  /**
   * Equal-weighted weekly return of the universe (all quant selection names): each name from last
   * week's closing day to this week's closing day (structural basis), averaged over names with bars
   * at both ends.
   */
  private double[] universeReturn(LocalDate prevEnd, LocalDate end) {
    List<InstrumentRow> all = targets.all();
    double sum = 0;
    int n = 0;
    for (int i = 0; i < all.size(); i += 50) {
      Map<Long, Instrument> chunk = new LinkedHashMap<>();
      all.subList(i, Math.min(all.size(), i + 50)).forEach(r -> chunk.put(r.id(), r.instrument()));
      Map<Long, List<DailyBar>> raw = bars.findMany(chunk, prevEnd, end);
      Map<Long, List<RehabFactor>> factors = rehabs.findMany(chunk);
      Set<LocalDate> calendar = new HashSet<>(days.between(Market.US, prevEnd, end));
      for (Long id : chunk.keySet()) {
        List<DailyBar> b = raw.getOrDefault(id, List.of());
        if (b.isEmpty()) {
          continue;
        }
        try {
          List<SignalBar> s =
              SignalTrades.scaledTo(b, factors.getOrDefault(id, List.of()), calendar, end, end);
          if (s.size() >= 2
              && s.getFirst().date().equals(prevEnd)
              && s.getLast().date().equals(end)) {
            sum += s.getLast().close() / s.getFirst().close() - 1;
            n++;
          }
        } catch (IllegalStateException ex) {
          // Names whose structural basis cannot be computed (missing ratio) are left out of the
          // mean, not guessed
        }
      }
    }
    return new double[] {n == 0 ? Double.NaN : sum / n, n};
  }

  // ------------------------------------------------------------------ audit

  /**
   * Merged into the quant selection audit (section six of check-daily). With trades in progress,
   * "computed through the day" is a critical check; take-profit alerts are an informational to-do
   * item.
   */
  public List<BarAuditService.Check> auditChecks(LocalDate d) {
    List<SimTradeRow> active = sim.list(null).stream().filter(SimTradeRow::active).toList();
    List<SimTradeRow> dueOnD =
        active.stream().filter(t -> !t.scoreDate().isAfter(d)).filter(t -> due(t, d)).toList();
    List<String> behind =
        dueOnD.stream()
            .filter(t -> t.updatedThrough() == null || t.updatedThrough().isBefore(d))
            .map(
                t ->
                    t.symbol()
                        + " #"
                        + t.id()
                        + " 算到 "
                        + (t.updatedThrough() == null ? "（从未）" : t.updatedThrough()))
            .toList();
    int later = active.size() - dueOnD.size();
    String waiting = later == 0 ? "" : "；另有 " + later + " 笔待成交、入场日在 " + d + " 之后，当天没有要算的";
    List<BarAuditService.Check> out = new ArrayList<>();
    out.add(
        new BarAuditService.Check(
            "simUpdated",
            behind.isEmpty(),
            !dueOnD.isEmpty(),
            active.isEmpty()
                ? "模拟池没有进行中的交易"
                : behind.isEmpty()
                    ? (dueOnD.isEmpty()
                        ? "进行中 " + active.size() + " 笔都还没到入场日，当天没有要算的"
                        : dueOnD.size() + " 笔都已算到 " + d + waiting)
                    : behind.size() + " 笔没有算到 " + d + waiting,
            behind.size(),
            behind));
    List<String> alerts =
        active.stream()
            .filter(
                t ->
                    "OPEN".equals(t.status())
                        && t.targetHitDate() != null
                        && t.closeRequestedAt() == null)
            .map(
                t ->
                    t.symbol()
                        + " #"
                        + t.id()
                        + " "
                        + t.targetHitDate()
                        + " 触及目标 "
                        + fmt(t.target().doubleValue())
                        + "，最近收盘 "
                        + fmt(t.lastClose().doubleValue())
                        + "（"
                        + t.lastCloseDate()
                        + "）")
            .toList();
    out.add(
        new BarAuditService.Check(
            "simTargetHits",
            alerts.isEmpty(),
            false,
            alerts.isEmpty() ? "没有待处理的止盈提示" : alerts.size() + " 笔已触及止盈目标，等你决定是否平仓",
            alerts.size(),
            alerts));
    return out;
  }

  // ------------------------------------------------------------------ helpers

  /**
   * Runs body under the shared lock. Waits up to 60 seconds: a REST add / close arriving while the
   * daily recompute holds the lock fails with a "retry later" message instead of blocking the
   * request thread indefinitely.
   */
  private <T> T locked(Supplier<T> body) {
    try {
      if (!lock.tryLock(60, TimeUnit.SECONDS)) {
        throw new IllegalStateException("模拟交易正在重算，稍后再试");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("被中断");
    }
    try {
      return body.get();
    } finally {
      lock.unlock();
    }
  }

  private static SimTradeRow with(SimTradeRow t, String status, String cancelReason) {
    return new SimTradeRow(
        t.id(),
        t.rulesVersion(),
        t.instrumentId(),
        t.symbol(),
        t.modelVersion(),
        t.scoreDate(),
        t.scoreRank(),
        t.rating(),
        t.composite(),
        t.requestedAt(),
        t.setupClose(),
        t.setupAtr(),
        t.formulaStop(),
        t.stop(),
        t.stopLeg(),
        t.resistance(),
        t.plannedEntryDate(),
        status,
        t.entryDate(),
        t.entryPrice(),
        t.shares(),
        t.plusOneR(),
        t.target(),
        t.targetSource(),
        t.touchedPlusOneR(),
        t.targetHitDate(),
        t.activeStop(),
        t.chandelierActive(),
        t.closeRequestedAt(),
        t.plannedExitDate(),
        t.closeKind(),
        t.closeNote(),
        t.exitDate(),
        t.exitPrice(),
        t.exitReason(),
        t.pnl(),
        t.returnPct(),
        t.rMultiple(),
        t.mfeR(),
        t.maeR(),
        t.barsHeld(),
        t.lastClose(),
        t.lastCloseDate(),
        t.unrealizedPnl(),
        t.spyReturn(),
        t.excessReturn(),
        t.shadowStatus(),
        t.shadowExitDate(),
        t.shadowExitPrice(),
        t.shadowExitReason(),
        t.shadowPnl(),
        t.shadowReturn(),
        t.shadowLastClose(),
        cancelReason,
        t.updatedThrough(),
        t.updatedAt());
  }

  private static SimTradeRow through(SimTradeRow t, LocalDate through) {
    return new SimTradeRow(
        t.id(),
        t.rulesVersion(),
        t.instrumentId(),
        t.symbol(),
        t.modelVersion(),
        t.scoreDate(),
        t.scoreRank(),
        t.rating(),
        t.composite(),
        t.requestedAt(),
        t.setupClose(),
        t.setupAtr(),
        t.formulaStop(),
        t.stop(),
        t.stopLeg(),
        t.resistance(),
        t.plannedEntryDate(),
        t.status(),
        t.entryDate(),
        t.entryPrice(),
        t.shares(),
        t.plusOneR(),
        t.target(),
        t.targetSource(),
        t.touchedPlusOneR(),
        t.targetHitDate(),
        t.activeStop(),
        t.chandelierActive(),
        t.closeRequestedAt(),
        t.plannedExitDate(),
        t.closeKind(),
        t.closeNote(),
        t.exitDate(),
        t.exitPrice(),
        t.exitReason(),
        t.pnl(),
        t.returnPct(),
        t.rMultiple(),
        t.mfeR(),
        t.maeR(),
        t.barsHeld(),
        t.lastClose(),
        t.lastCloseDate(),
        t.unrealizedPnl(),
        t.spyReturn(),
        t.excessReturn(),
        t.shadowStatus(),
        t.shadowExitDate(),
        t.shadowExitPrice(),
        t.shadowExitReason(),
        t.shadowPnl(),
        t.shadowReturn(),
        t.shadowLastClose(),
        t.cancelReason(),
        through,
        t.updatedAt());
  }

  private static int count(List<SimTradeRow> all, String status) {
    return (int) all.stream().filter(t -> status.equals(t.status())).count();
  }

  private static String legText(SimSetup s) {
    return switch (s.leg()) {
      case ATR -> "收盘 − 2×ATR";
      case ZONE -> "支撑区底 − 0.5×ATR";
      case CAP -> "公式止损 " + fmt(s.formulaStop()) + " 距离超过 10%，封顶";
    };
  }

  private static String reasonText(String r) {
    return switch (r) {
      case "STOP" -> "收盘跌破止损，按收盘平仓";
      case "CHANDELIER" -> "收盘跌破吊灯止损，按收盘平仓";
      case "TAKE_PROFIT" -> "止盈请求，开盘平仓";
      case "MANUAL" -> "手动请求，开盘平仓";
      default -> r;
    };
  }

  private static Double dbl(BigDecimal x) {
    return x == null ? null : x.doubleValue();
  }

  static BigDecimal d6(double v) {
    return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
  }

  static BigDecimal d2(double v) {
    return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
  }

  private static double round2(double v) {
    return d2(v).doubleValue();
  }

  private static String fmt(double x) {
    return BigDecimal.valueOf(x)
        .setScale(4, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString();
  }

  private static String fmt2(double x) {
    return d2(x).toPlainString();
  }

  private static String pct(BigDecimal ratio) {
    return ratio == null ? "—" : pct(ratio.doubleValue());
  }

  private static String pct(Double ratio) {
    return ratio == null
        ? "—"
        : BigDecimal.valueOf(ratio * 100).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
  }
}
