package org.jdkxx.trader.domain.selection.sim;

import org.jdkxx.trader.domain.signal.ExitPlan;
import org.jdkxx.trader.domain.signal.GateResult;
import org.jdkxx.trader.domain.signal.SentinelEvaluation;

import java.time.LocalDate;
import java.util.Map;

/**
 * 加入模拟池时定下的价位（评分日口径 = 当天原始价），之后不再变。复用入场信号第四门的止损公式与出场预案的压力区，
 * 但<b>不要求四门通过</b>——这里只借它的风控与价位，入场理由是评分排名。
 *
 * @param formulaStop 公式算出的止损（未封顶）
 * @param stop        实际止损：距离超过上限时上移到上限（leg = CAP）
 * @param resistance  上方最近压力区的区底（评分日口径），没有为 null
 */
public record SimSetup(LocalDate setupDate, double close, double atr, double formulaStop, double stop, StopLeg leg,
                       Double resistance) {

    public enum StopLeg {
        /** 收盘 − 2×ATR */
        ATR,
        /** 支撑区底 − 0.5×ATR（比 ATR 腿更低时） */
        ZONE,
        /** 公式止损距离超过上限，封顶 */
        CAP
    }

    /** 从评分日的四门判定里取价位；不予判定或第四门不可判定时抛 IllegalStateException（说明原因，不猜）。 */
    public static SimSetup from(SentinelEvaluation e, SimRules rules) {
        if (e.status() != SentinelEvaluation.Status.EVALUATED) {
            throw new IllegalStateException(e.asOf() + " 无法定止损：" + e.status() + "（" + e.statusDetail() + "）");
        }
        GateResult risk = e.gates().stream().filter(g -> g.gate() == GateResult.Gate.RISK).findFirst()
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
