package org.jdkxx.trader.domain.signal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Four-gate result for one instrument on one evaluation day (before edge, cooldown and AI
 * suppression, which are the job of {@link SignalSuppression}).
 *
 * @param status EVALUATED, or the data-level reason for not evaluating
 * @param gates the four gate conclusions in gate order; empty when not evaluated
 * @param indicators indicator values on the evaluation day (fixed key order)
 * @param zones valid support zones within the lookback, ascending by bottom
 * @param hitZone the support zone that was hit (the one with the highest top when several are hit),
 *     null if none
 * @param exitPlan exit plan; null when the fourth gate could not be evaluated
 * @param bonus bonus items (do not affect pass/fail): Fibonacci confluence, positive MACD histogram
 */
public record SentinelEvaluation(
    String version,
    LocalDate asOf,
    Status status,
    String statusDetail,
    List<GateResult> gates,
    Map<String, Object> indicators,
    List<PriceZone> zones,
    PriceZone hitZone,
    ExitPlan exitPlan,
    Map<String, Object> bonus) {

  public enum Status {
    EVALUATED,
    /** Fewer bars in the window than the minimum */
    SKIPPED_INSUFFICIENT_BARS,
    /**
     * The latest bar is not the expected trading day: evaluating stale data would produce a
     * well-formed but wrong signal without any error
     */
    SKIPPED_STALE_DATA,
    /**
     * More trading days missing in the window than tolerated (checked against the trading calendar)
     */
    SKIPPED_DATA_GAP,
    /**
     * A share-count event in the window lacks its ratio, or an event type that was never verified
     * appeared: the price/volume basis cannot be computed
     */
    SKIPPED_CORPORATE_ACTION
  }

  public SentinelEvaluation {
    gates = List.copyOf(gates);
    zones = List.copyOf(zones);
  }

  public static SentinelEvaluation skipped(
      String version, LocalDate asOf, Status status, String detail) {
    return new SentinelEvaluation(
        version, asOf, status, detail, List.of(), Map.of(), List.of(), null, null, Map.of());
  }

  public boolean allPassed() {
    return status == Status.EVALUATED && gates.stream().allMatch(GateResult::passed);
  }

  public int gatesPassed() {
    return (int) gates.stream().filter(GateResult::passed).count();
  }

  /** The first gate that did not pass (FAIL or UNAVAILABLE). */
  public Optional<GateResult.Gate> firstBlockingGate() {
    return gates.stream().filter(g -> !g.passed()).map(GateResult::gate).findFirst();
  }
}
