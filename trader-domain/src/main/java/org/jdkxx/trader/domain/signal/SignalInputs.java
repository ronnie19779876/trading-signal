package org.jdkxx.trader.domain.signal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.RehabFactor;

/**
 * Data-level checks and basis conversion before evaluation (pure functions), in this order:
 *
 * <ol>
 *   <li>cut the window: {@code windowCalendarDays} calendar days back from the evaluation day
 *       (inclusive);
 *   <li>drop bars outside the trading calendar: Futu has left dirty bars on US market holidays (SPY
 *       2011-07-04 and others, with abnormal lows), which would otherwise be detected as fractal
 *       lows and invent support zones;
 *   <li>no bar on the evaluation day: SKIPPED_STALE_DATA;
 *   <li>more trading days missing between the first bar and the evaluation day (checked against the
 *       calendar; suspension blank bars count as missing) than tolerated: SKIPPED_DATA_GAP. Futu's
 *       SPY history once lacked 26 trading days with previous closes consistent across the gap, so
 *       a continuity check cannot find it; only the calendar can;
 *   <li>structural basis conversion ({@link StructuralAdjustment}); if it cannot be computed:
 *       SKIPPED_CORPORATE_ACTION.
 * </ol>
 */
public final class SignalInputs {

  private SignalInputs() {}

  /**
   * @param skipped the result when nothing is evaluated; {@code bars} is then empty
   * @param fingerprint input fingerprint: the first 32 hex characters (16 bytes) of the SHA-256
   *     over the raw bars in the window (including dropped and blank bars) + the factors of events
   *     going ex within the window + the criteria version. Evaluation is deterministic, so a
   *     different fingerprint on re-evaluation can only mean bars or factors were re-fetched and
   *     changed (measured: volume gets revised upwards the next day)
   */
  public record Prepared(
      List<SignalBar> bars,
      SentinelEvaluation skipped,
      List<LocalDate> droppedNonTradingDays,
      List<LocalDate> missingTradingDays,
      String fingerprint) {}

  /**
   * @param raw unadjusted daily bars; may be longer than the window (replays load several years at
   *     once)
   * @param tradingDays trading calendar covering at least the window
   * @throws IllegalArgumentException when {@code asOf} is not a trading day
   */
  public static Prepared prepare(
      List<DailyBar> raw,
      List<RehabFactor> factors,
      Collection<LocalDate> tradingDays,
      LocalDate asOf,
      SentinelThresholds th) {
    Set<LocalDate> calendar =
        tradingDays instanceof Set<LocalDate> s ? s : new HashSet<>(tradingDays);
    if (!calendar.contains(asOf)) {
      throw new IllegalArgumentException(asOf + " 不是交易日");
    }
    LocalDate from = asOf.minusDays(th.windowCalendarDays());
    List<DailyBar> window = new ArrayList<>();
    List<LocalDate> dropped = new ArrayList<>();
    String fingerprint = fingerprint(raw, factors, from, asOf, th);
    for (DailyBar b : raw) {
      if (b.tradeDate().isBefore(from) || b.tradeDate().isAfter(asOf)) {
        continue;
      }
      if (calendar.contains(b.tradeDate())) {
        window.add(b);
      } else {
        dropped.add(b.tradeDate());
      }
    }
    if (window.isEmpty() || !window.get(window.size() - 1).tradeDate().equals(asOf)) {
      String latest =
          window.isEmpty() ? "窗口内没有 K 线" : "最新 K 线是 " + window.get(window.size() - 1).tradeDate();
      return skipped(
          th,
          asOf,
          SentinelEvaluation.Status.SKIPPED_STALE_DATA,
          "判定日没有 K 线，" + latest,
          dropped,
          List.of(),
          fingerprint);
    }

    Set<LocalDate> present = new HashSet<>();
    window.stream().filter(b -> !b.blank()).forEach(b -> present.add(b.tradeDate()));
    LocalDate first = window.get(0).tradeDate();
    List<LocalDate> missing =
        new TreeSet<>(calendar)
            .subSet(first, true, asOf, true).stream().filter(d -> !present.contains(d)).toList();
    if (missing.size() > th.maxMissingTradingDays()) {
      return skipped(
          th,
          asOf,
          SentinelEvaluation.Status.SKIPPED_DATA_GAP,
          "窗口内缺 "
              + missing.size()
              + " 个交易日（容忍 "
              + th.maxMissingTradingDays()
              + "），最早 "
              + missing.get(0)
              + "、最晚 "
              + missing.get(missing.size() - 1),
          dropped,
          missing,
          fingerprint);
    }

    StructuralAdjustment.Result adjusted = StructuralAdjustment.apply(window, factors, asOf);
    if (!adjusted.ok()) {
      return skipped(
          th,
          asOf,
          SentinelEvaluation.Status.SKIPPED_CORPORATE_ACTION,
          adjusted.problem(),
          dropped,
          missing,
          fingerprint);
    }
    return new Prepared(
        adjusted.bars(), null, List.copyOf(dropped), List.copyOf(missing), fingerprint);
  }

  /** Data-level checks followed by the four-gate evaluation. */
  public static SentinelEvaluation judge(
      List<DailyBar> raw,
      List<RehabFactor> factors,
      Collection<LocalDate> tradingDays,
      LocalDate asOf,
      SentinelThresholds th) {
    Prepared p = prepare(raw, factors, tradingDays, asOf, th);
    return p.skipped() != null ? p.skipped() : SentinelEvaluator.evaluate(p.bars(), asOf, th);
  }

  private static Prepared skipped(
      SentinelThresholds th,
      LocalDate asOf,
      SentinelEvaluation.Status status,
      String detail,
      List<LocalDate> dropped,
      List<LocalDate> missing,
      String fingerprint) {
    return new Prepared(
        List.of(),
        SentinelEvaluation.skipped(th.version(), asOf, status, detail),
        List.copyOf(dropped),
        List.copyOf(missing),
        fingerprint);
  }

  /**
   * Numbers always use BigDecimal's canonical text with trailing zeros stripped, so the fingerprint
   * does not depend on the scale of the database's numeric columns. Factors are those with exDate
   * in (from, asOf], i.e. exactly the events that can affect a bar inside the window.
   */
  static String fingerprint(
      List<DailyBar> raw,
      List<RehabFactor> factors,
      LocalDate from,
      LocalDate asOf,
      SentinelThresholds th) {
    StringBuilder sb = new StringBuilder(th.version()).append('\n');
    for (DailyBar b : raw) {
      if (b.tradeDate().isBefore(from) || b.tradeDate().isAfter(asOf)) {
        continue;
      }
      sb.append(b.tradeDate())
          .append('|')
          .append(n(b.open()))
          .append('|')
          .append(n(b.high()))
          .append('|')
          .append(n(b.low()))
          .append('|')
          .append(n(b.close()))
          .append('|')
          .append(b.volume())
          .append('|')
          .append(b.blank())
          .append('\n');
    }
    factors.stream()
        .filter(f -> f.exDate().isAfter(from) && !f.exDate().isAfter(asOf))
        .sorted(java.util.Comparator.comparing(RehabFactor::exDate))
        .forEach(
            f ->
                sb.append(f.exDate())
                    .append('|')
                    .append(f.companyActFlag())
                    .append('|')
                    .append(n(f.fwdA()))
                    .append('|')
                    .append(n(f.fwdB()))
                    .append('|')
                    .append(f.splitBase())
                    .append(':')
                    .append(f.splitErt())
                    .append('|')
                    .append(f.joinBase())
                    .append(':')
                    .append(f.joinErt())
                    .append('|')
                    .append(f.bonusBase())
                    .append(':')
                    .append(f.bonusErt())
                    .append('|')
                    .append(f.transferBase())
                    .append(':')
                    .append(f.transferErt())
                    .append('\n'));
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest, 0, 16);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String n(java.math.BigDecimal x) {
    return x == null ? "" : x.stripTrailingZeros().toPlainString();
  }
}
