package org.jdkxx.trader.core.signal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdkxx.trader.core.marketdata.InstrumentDirectory;
import org.jdkxx.trader.core.marketdata.bars.SettledCutoff;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.domain.signal.GateResult;
import org.jdkxx.trader.domain.signal.PaperTrade;
import org.jdkxx.trader.domain.signal.SentinelEvaluation;
import org.jdkxx.trader.domain.signal.SentinelThresholds;
import org.jdkxx.trader.domain.signal.SignalInputs;
import org.jdkxx.trader.domain.signal.SignalSuppression;
import org.jdkxx.trader.domain.signal.SignalTrades;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;

/**
 * Read-only computations of the entry sentinel: single-day judgement and range replay. No
 * persistence, no gateway calls, no model calls (batch runs and persistence belong to step 3). The
 * judgement date must not be later than the latest settled trading day ({@link SettledCutoff});
 * intraday prices never take part in a judgement.
 */
public class SentinelService {

  /** Replay range cap: pool and holdings have 20 years of daily bars. */
  static final int MAX_REPLAY_YEARS = 21;

  /**
   * Warm-up before the replay range, in cooldown windows. Live judges the edge against the previous
   * trading day and reads the cooldown from the signals already stored; the replay used to start
   * both from scratch, so a second consecutive passing day at the start of the range came out as a
   * fresh SIGNAL (found in the 2026-10-31 review). Whether a day signals depends on the signals of
   * the cooldown window before it, so the warm-up runs several windows to let that chain settle.
   */
  static final int WARMUP_COOLDOWNS = 4;

  private final InstrumentDirectory directory;
  private final DailyBarRepository bars;
  private final RehabFactorRepository rehabs;
  private final TradingDayRepository days;
  private final SettledCutoff cutoff;
  private final SentinelThresholds thresholds;

  public SentinelService(
      InstrumentDirectory directory,
      DailyBarRepository bars,
      RehabFactorRepository rehabs,
      TradingDayRepository days,
      SettledCutoff cutoff) {
    this.directory = directory;
    this.bars = bars;
    this.rehabs = rehabs;
    this.days = days;
    this.cutoff = cutoff;
    this.thresholds = SentinelThresholds.V1;
  }

  public record Judgement(
      String symbol,
      SentinelEvaluation evaluation,
      List<LocalDate> droppedNonTradingDays,
      List<LocalDate> missingTradingDays,
      String fingerprint,
      Map<String, Object> thresholds) {}

  /** Single-day judgement. A null date means the latest settled trading day. */
  public Judgement evaluate(String symbol, LocalDate date) {
    LocalDate settled = cutoff.current();
    LocalDate asOf = date == null ? settled : date;
    if (asOf.isAfter(settled)) {
      throw new IllegalStateException("判定日 " + asOf + " 晚于收盘落定的交易日 " + settled);
    }
    InstrumentRow row = directory.require(symbol);
    LocalDate from = asOf.minusDays(thresholds.windowCalendarDays());
    List<DailyBar> raw = bars.find(row.instrument(), row.id(), from, asOf);
    List<RehabFactor> factors = rehabs.find(row.instrument(), row.id());
    List<LocalDate> calendar = days.between(Market.US, from, asOf);
    SignalInputs.Prepared p = SignalInputs.prepare(raw, factors, calendar, asOf, thresholds);
    SentinelEvaluation e =
        p.skipped() != null
            ? p.skipped()
            : org.jdkxx.trader.domain.signal.SentinelEvaluator.evaluate(p.bars(), asOf, thresholds);
    return new Judgement(
        row.symbol(),
        e,
        p.droppedNonTradingDays(),
        p.missingTradingDays(),
        p.fingerprint(),
        thresholds.toMap());
  }

  public record ChartBar(
      LocalDate tradeDate, double open, double high, double low, double close, long volume) {}

  /**
   * Bars for charting, price scale anchored at asOf (aligned with that day's signal levels and
   * continuous across later splits). Defaults: to = settled date, asOf = to, from = one year before
   * asOf; the range is at most 3 years.
   */
  public List<ChartBar> chartBars(String symbol, LocalDate asOf, LocalDate from, LocalDate to) {
    LocalDate settled = cutoff.current();
    LocalDate end = to == null || to.isAfter(settled) ? settled : to;
    LocalDate anchor = asOf == null ? end : asOf;
    LocalDate start = from == null ? anchor.minusYears(1) : from;
    if (start.isAfter(end) || anchor.isAfter(end)) {
      throw new IllegalArgumentException("需要 from ≤ asOf ≤ to");
    }
    if (start.isBefore(end.minusYears(3))) {
      throw new IllegalArgumentException("区间不能超过 3 年");
    }
    InstrumentRow row = directory.require(symbol);
    List<DailyBar> raw = bars.find(row.instrument(), row.id(), start, end);
    Set<LocalDate> calendar = new HashSet<>(days.between(Market.US, start, end));
    return SignalTrades.scaledTo(
            raw, rehabs.find(row.instrument(), row.id()), calendar, end, anchor)
        .stream()
        .map(
            b ->
                new ChartBar(
                    b.date(),
                    round(b.open()),
                    round(b.high()),
                    round(b.low()),
                    round(b.close()),
                    Math.round(b.volume())))
        .toList();
  }

  private static double round(double v) {
    return java.math.BigDecimal.valueOf(v)
        .setScale(4, java.math.RoundingMode.HALF_UP)
        .doubleValue();
  }

  /**
   * One replay day. The outcome follows the edge and cooldown rules (there are no historical AI
   * verdicts, so "no verdict does not block" applies); the only difference from the live batch is
   * the AI veto layer. Like live, the first day's edge looks at the trading day before the range
   * and its cooldown at signals before the range: both are warmed up on the trading days before
   * {@code from} (see {@link #WARMUP_COOLDOWNS}).
   *
   * @param gates four-gate verdict letters in trend / location / trigger / risk order: P pass, F
   *     fail, U undecidable; null when not judged
   */
  public record ReplayDay(
      LocalDate date,
      SentinelEvaluation.Status status,
      String gates,
      int gatesPassed,
      GateResult.Gate firstBlockingGate,
      SignalSuppression.Outcome outcome,
      Double close,
      Double atr14,
      Double rvol,
      Double zoneBottom,
      Double stop,
      Double stopDistance,
      String detail) {}

  /**
   * @param trades paper trade for each SIGNAL ({@link PaperTrade}, prices on the replay end-date
   *     basis); null when not requested
   * @param exitVariant description of the exit variant used for the paper trades; the signal set is
   *     always judged by sentinel-v1, the variant only changes the exit, so the same signals can be
   *     compared pairwise
   */
  public record Replay(
      String symbol,
      LocalDate from,
      LocalDate to,
      Map<String, Long> statusCounts,
      Map<String, Long> outcomeCounts,
      List<ReplayDay> days,
      String exitVariant,
      List<PaperTrade.Result> trades) {}

  public Replay replay(String symbol, LocalDate from, LocalDate to) {
    return replay(symbol, from, to, false, null, false);
  }

  /**
   * @param stopAtrMultiple stop ATR multiple for the paper trades (for pairwise comparison; changes
   *     only the exit, not the judgement); null means sentinel-v1's 2.0
   * @param halfAtPlusOneR whether the paper trades sell half at +1R (comparison variant)
   */
  public Replay replay(
      String symbol,
      LocalDate from,
      LocalDate to,
      boolean withTrades,
      Double stopAtrMultiple,
      boolean halfAtPlusOneR) {
    LocalDate settled = cutoff.current();
    LocalDate end = to == null || to.isAfter(settled) ? settled : to;
    LocalDate start = from == null ? end.minusYears(1) : from;
    if (start.isAfter(end)) {
      throw new IllegalArgumentException("from 不能晚于 to");
    }
    if (start.isBefore(end.minusYears(MAX_REPLAY_YEARS))) {
      throw new IllegalArgumentException("回放区间不能超过 " + MAX_REPLAY_YEARS + " 年");
    }
    InstrumentRow row = directory.require(symbol);
    int warmupDays = WARMUP_COOLDOWNS * thresholds.cooldownDays();
    // Twice the warm-up length in calendar days always holds that many trading days; the bars go
    // back one more data window so the first warm-up day is judged on a complete window
    LocalDate loadFrom = start.minusDays(2L * warmupDays + thresholds.windowCalendarDays());
    // Paper trades run past the end of the replay range, so bars are fetched up to the settled date
    List<DailyBar> raw =
        bars.find(row.instrument(), row.id(), loadFrom, withTrades ? settled : end);
    List<RehabFactor> factors = rehabs.find(row.instrument(), row.id());
    List<LocalDate> calendarList = days.between(Market.US, loadFrom, end);
    Set<LocalDate> calendar = new HashSet<>(calendarList);

    List<ReplayDay> out = new ArrayList<>();
    Map<String, Long> statusCounts = new LinkedHashMap<>();
    Map<String, Long> outcomeCounts = new LinkedHashMap<>();
    // Edge and cooldown state are warmed up on the last `warmupDays` trading days before `start`;
    // those days are judged but produce no output, counts or paper trades
    int firstInRange = 0;
    while (firstInRange < calendarList.size() && calendarList.get(firstInRange).isBefore(start)) {
      firstInRange++;
    }
    boolean previousPassed = false;
    Integer sinceLastSignal = null;
    List<SentinelEvaluation> signals = new ArrayList<>();
    int lo = 0;
    for (LocalDate asOf :
        calendarList.subList(Math.max(0, firstInRange - warmupDays), calendarList.size())) {
      boolean warmup = asOf.isBefore(start);
      // Hand only the bars inside the window to the judgement, so each day does not rescan the
      // whole history
      LocalDate windowStart = asOf.minusDays(thresholds.windowCalendarDays());
      while (lo < raw.size() && raw.get(lo).tradeDate().isBefore(windowStart)) {
        lo++;
      }
      int hi = lo;
      while (hi < raw.size() && !raw.get(hi).tradeDate().isAfter(asOf)) {
        hi++;
      }
      SentinelEvaluation e =
          SignalInputs.judge(raw.subList(lo, hi), factors, calendar, asOf, thresholds);
      if (sinceLastSignal != null) {
        sinceLastSignal++;
      }
      SignalSuppression.Outcome outcome =
          SignalSuppression.afterAi(
              SignalSuppression.beforeAi(
                  e.allPassed(),
                  previousPassed
                      ? SignalSuppression.PreviousDay.PASSED
                      : SignalSuppression.PreviousDay.NOT_PASSED,
                  sinceLastSignal,
                  thresholds),
              SignalSuppression.AiVerdict.ABSENT);
      if (outcome == SignalSuppression.Outcome.SIGNAL) {
        sinceLastSignal = 0;
        if (!warmup) {
          signals.add(e);
        }
      }
      previousPassed = e.allPassed();
      if (warmup) {
        continue;
      }

      GateResult risk =
          e.gates().stream().filter(g -> g.gate() == GateResult.Gate.RISK).findFirst().orElse(null);
      String gates =
          e.gates().isEmpty()
              ? null
              : e.gates().stream()
                  .map(g -> g.verdict().name().substring(0, 1))
                  .reduce("", String::concat);
      out.add(
          new ReplayDay(
              asOf,
              e.status(),
              gates,
              e.gatesPassed(),
              e.firstBlockingGate().orElse(null),
              outcome,
              (Double) e.indicators().get("close"),
              (Double) e.indicators().get("atr14"),
              (Double) e.indicators().get("rvol"),
              e.hitZone() == null ? null : e.hitZone().bottom(),
              risk == null ? null : (Double) risk.values().get("stop"),
              risk == null ? null : (Double) risk.values().get("stopDistance"),
              e.statusDetail()));
      statusCounts.merge(e.status().name(), 1L, Long::sum);
      outcomeCounts.merge(outcome.name(), 1L, Long::sum);
    }
    if (!withTrades) {
      return new Replay(row.symbol(), start, end, statusCounts, outcomeCounts, out, null, null);
    }
    double multiple = stopAtrMultiple == null ? thresholds.stopAtrMultiple() : stopAtrMultiple;
    if (!(multiple > 0 && multiple <= 10)) {
      throw new IllegalArgumentException("stopAtr 取值 (0, 10]");
    }
    PaperTrade.Rules rules = PaperTrade.Rules.of(thresholds);
    if (halfAtPlusOneR) {
      rules = rules.withHalfAtPlusOneR();
    }
    String variant =
        "止损 min(收盘 − "
            + multiple
            + "×ATR, 区底 − "
            + thresholds.zoneStopAtrMultiple()
            + "×ATR)"
            + (halfAtPlusOneR ? "，+1R 减半仓" : "，不减半仓");
    return new Replay(
        row.symbol(),
        start,
        end,
        statusCounts,
        outcomeCounts,
        out,
        variant,
        trades(raw, factors, settled, signals, multiple, rules));
  }

  /**
   * Simulates each signal (same entry point as the paper ledger, {@link SignalTrades}): prices on
   * the judgement-date basis.
   */
  private List<PaperTrade.Result> trades(
      List<DailyBar> raw,
      List<RehabFactor> factors,
      LocalDate settled,
      List<SentinelEvaluation> signals,
      double stopAtrMultiple,
      PaperTrade.Rules rules) {
    LocalDate first = raw.isEmpty() ? settled : raw.get(0).tradeDate();
    Set<LocalDate> tradingDays = new HashSet<>(days.between(Market.US, first, settled));
    List<PaperTrade.Result> out = new ArrayList<>();
    for (SentinelEvaluation e : signals) {
      double close = (Double) e.indicators().get("close");
      double atr = (Double) e.indicators().get("atr14");
      double stop =
          SignalTrades.stop(
              close,
              atr,
              e.hitZone() == null ? null : e.hitZone().bottom(),
              stopAtrMultiple,
              thresholds);
      PaperTrade.Result r =
          SignalTrades.simulate(
              raw, factors, tradingDays, e.asOf(), close, stop, settled, thresholds, rules);
      if (r != null) {
        out.add(r);
      }
    }
    return out;
  }
}
