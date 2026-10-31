package org.jdkxx.trader.domain.selection.sim;

import java.time.LocalDate;
import java.util.Map;
import org.jdkxx.trader.domain.signal.ExitPlan;
import org.jdkxx.trader.domain.signal.GateResult;
import org.jdkxx.trader.domain.signal.SentinelEvaluation;

/**
 * Price levels fixed when a trade is added to the simulation (scoring-day basis = that day's raw
 * prices); they never change afterwards. Reuses the entry signal's fourth-gate stop formula and the
 * exit plan's resistance zone, but <b>does not require the four gates to pass</b>: only the risk
 * logic and levels are borrowed, the reason for entry is the score ranking.
 *
 * @param formulaStop stop from the formula (before the cap)
 * @param stop actual stop: raised to the cap when the distance exceeds it (leg = CAP)
 * @param resistance bottom of the nearest resistance zone above (scoring-day basis), null if none
 */
public record SimSetup(
    LocalDate setupDate,
    double close,
    double atr,
    double formulaStop,
    double stop,
    StopLeg leg,
    Double resistance) {

  public enum StopLeg {
    /** Close − 2×ATR */
    ATR,
    /** Support zone bottom − 0.5×ATR (when lower than the ATR leg) */
    ZONE,
    /** The formula stop's distance exceeds the maximum, so it is capped */
    CAP
  }

  /**
   * Takes the levels from the scoring day's four-gate evaluation; throws IllegalStateException
   * (with the reason, no guessing) when the day was not evaluated or the fourth gate could not be
   * evaluated. The values are the persisted 6-decimal roundings from the risk gate.
   */
  public static SimSetup from(SentinelEvaluation e, SimRules rules) {
    if (e.status() != SentinelEvaluation.Status.EVALUATED) {
      throw new IllegalStateException(
          e.asOf() + " 无法定止损：" + e.status() + "（" + e.statusDetail() + "）");
    }
    GateResult risk =
        e.gates().stream()
            .filter(g -> g.gate() == GateResult.Gate.RISK)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(e.asOf() + " 判定结果里没有风控门"));
    ExitPlan plan = e.exitPlan();
    if (risk.verdict() == GateResult.Verdict.UNAVAILABLE || plan == null) {
      throw new IllegalStateException(e.asOf() + " 无法定止损：" + risk.criteria());
    }
    Map<String, Object> v = risk.values();
    double close = number(v, "close");
    double atr = number(v, "atr14");
    double formula = number(v, "stop");
    StopLeg leg = "ZONE".equals(v.get("stopLeg")) ? StopLeg.ZONE : StopLeg.ATR;
    double floor = close * (1 - rules.maxStopDistance());
    double stop = formula;
    if (formula < floor) {
      stop = floor;
      leg = StopLeg.CAP;
    }
    return new SimSetup(e.asOf(), close, atr, formula, stop, leg, plan.target());
  }

  private static double number(Map<String, Object> v, String key) {
    Object x = v.get(key);
    if (!(x instanceof Number n)) {
      throw new IllegalStateException("风控门缺少 " + key);
    }
    return n.doubleValue();
  }
}
