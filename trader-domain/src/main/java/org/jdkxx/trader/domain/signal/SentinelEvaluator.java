package org.jdkxx.trader.domain.signal;

import org.jdkxx.trader.domain.signal.GateResult.Gate;
import org.jdkxx.trader.domain.signal.GateResult.Verdict;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 入场哨兵四门判定（确定性纯函数：相同 K 线、相同参数必然得出相同结论）。
 *
 * <ol>
 *   <li>趋势：收盘 &gt; SMA200 且 SMA200[t] &gt; SMA200[t−20]；</li>
 *   <li>定位：判定日往前 252 根以内（t − j ≤ 252，含判定日共 253 根，与 entry-v3 生产引擎一致）的分形低点按 τ = 0.5×ATR14 聚类，触及 ≥ 2 次为有效区；当日最低 ≤ 区顶 且 收盘 ≥ 区底。
 *       同时命中多个区时取区顶最高者（离现价最近、止损最紧，结论唯一）；</li>
 *   <li>触发：RVOL ≥ 1.5（前 20 日均量，不含当日）且 收盘 &gt; 开盘；</li>
 *   <li>风控：止损 = min(收盘 − 2×ATR14, 区底 − 0.5×ATR14)，没有命中区时只用 ATR 腿；止损距离 ≤ 10%。</li>
 * </ol>
 * 四门为逻辑与，不加权、不综合评分；四门都照常计算，不短路，以支持逐门复核。
 *
 * <p>取数窗口固定为 判定日往前 {@code windowCalendarDays} 个自然日（含边界）：Wilder ATR 与 EMA 是递推的、与路径有关，
 * 窗口起点变了结果就变。调用方可以传入更长的历史，这里统一截取。
 */
public final class SentinelEvaluator {

    private SentinelEvaluator() {
    }

    public static SentinelEvaluation evaluate(List<SignalBar> history, LocalDate asOf, SentinelThresholds th) {
        SignalBar.requireAscending(history);
        LocalDate windowStart = asOf.minusDays(th.windowCalendarDays());
        List<SignalBar> bars = history.stream()
                .filter(b -> !b.date().isBefore(windowStart) && !b.date().isAfter(asOf))
                .toList();
        if (bars.isEmpty() || !bars.get(bars.size() - 1).date().equals(asOf)) {
            return SentinelEvaluation.skipped(th.version(), asOf, SentinelEvaluation.Status.SKIPPED_STALE_DATA,
                    "判定日没有 K 线" + (bars.isEmpty() ? "" : "，窗口内最新为 " + bars.get(bars.size() - 1).date()));
        }
        if (bars.size() < th.minBars()) {
            return SentinelEvaluation.skipped(th.version(), asOf, SentinelEvaluation.Status.SKIPPED_INSUFFICIENT_BARS,
                    "窗口 " + windowStart + " 至 " + asOf + " 只有 " + bars.size() + " 根，少于 " + th.minBars());
        }
        return new Computation(bars, th).run();
    }

    /** 一次判定的中间量，只在 {@link #evaluate} 内存活。 */
    private static final class Computation {
        private final SentinelThresholds th;
        private final int n;
        private final int t;
        private final LocalDate[] date;
        private final double[] open, high, low, close, volume;
        private final double[] sma, atr, macd;

        Computation(List<SignalBar> bars, SentinelThresholds th) {
            this.th = th;
            n = bars.size();
            t = n - 1;
            date = new LocalDate[n];
            open = new double[n];
            high = new double[n];
            low = new double[n];
            close = new double[n];
            volume = new double[n];
            for (int i = 0; i < n; i++) {
                SignalBar b = bars.get(i);
                date[i] = b.date();
                open[i] = b.open();
                high[i] = b.high();
                low[i] = b.low();
                close[i] = b.close();
                volume[i] = b.volume();
            }
            sma = Indicators.sma(close, th.smaLong());
            atr = Indicators.wilderAtr(high, low, close, th.atrPeriod());
            macd = Indicators.macdHistogram(close, th.macdFast(), th.macdSlow(), th.macdSignal());
        }

        SentinelEvaluation run() {
            double atrT = atr[t];
            double tau = th.zoneToleranceAtr() * atrT;
            int from = Math.max(0, t - th.zoneLookback());
            List<PriceZone> zones = Double.isNaN(tau) ? List.of()
                    : Fractals.cluster(Fractals.lows(low, th.fractalSide(), from), low, date, tau, th.minTouches());

            GateResult trend = trend();
            PriceZone hit = zones.stream()
                    .filter(z -> low[t] <= z.top() && close[t] >= z.bottom())
                    .max(Comparator.comparingDouble(PriceZone::top))
                    .orElse(null);
            GateResult location = location(atrT, tau, zones, hit);
            GateResult trigger = trigger();
            GateResult risk = risk(atrT, hit);

            Map<String, Object> indicators = new LinkedHashMap<>();
            indicators.put("close", r(close[t]));
            indicators.put("open", r(open[t]));
            indicators.put("low", r(low[t]));
            indicators.put("sma200", r(sma[t]));
            indicators.put("sma200Prior", t >= th.smaSlopeLookback() ? r(sma[t - th.smaSlopeLookback()]) : null);
            indicators.put("atr14", r(atrT));
            indicators.put("tau", r(tau));
            indicators.put("rvol", r(Indicators.relativeVolume(volume, t, th.rvolLookback())));
            indicators.put("avgVolume20", r(Indicators.priorAverageVolume(volume, t, th.rvolLookback())));
            indicators.put("macdHistogram", r(macd[t]));
            indicators.put("bars", n);
            indicators.put("windowStart", date[0].toString());

            return new SentinelEvaluation(th.version(), date[t], SentinelEvaluation.Status.EVALUATED, null,
                    List.of(trend, location, trigger, risk), indicators, zones, hit,
                    exitPlan(atrT, risk), bonus(tau, hit));
        }

        private GateResult trend() {
            int k = th.smaSlopeLookback();
            double now = sma[t];
            double prior = t >= k ? sma[t - k] : Double.NaN;
            Map<String, Object> v = values("close", close[t], "sma200", now, "sma200Prior", prior);
            if (Double.isNaN(now) || Double.isNaN(prior)) {
                return new GateResult(Gate.TREND, Verdict.UNAVAILABLE, "SMA200 或其 " + k + " 日前的值样本不足", v);
            }
            boolean aboveMa = greater(close[t], now);
            boolean rising = greater(now, prior);
            boolean pass = aboveMa && rising;
            String criteria = "收盘 " + f(close[t]) + (aboveMa ? " > " : " ≤ ") + "SMA200 " + f(now)
                    + "（" + pct(close[t] / now - 1, true) + "）；SMA200 " + f(now) + (rising ? " > " : " ≤ ")
                    + k + " 日前 " + f(prior);
            return new GateResult(Gate.TREND, pass ? Verdict.PASS : Verdict.FAIL, criteria, v);
        }

        private GateResult location(double atrT, double tau, List<PriceZone> zones, PriceZone hit) {
            Map<String, Object> v = values("low", low[t], "close", close[t], "atr14", atrT, "tau", tau);
            v.put("zoneCount", zones.size());
            if (Double.isNaN(tau)) {
                return new GateResult(Gate.LOCATION, Verdict.UNAVAILABLE, "ATR14 样本不足，无法聚类", v);
            }
            if (hit != null) {
                v.put("zoneBottom", r(hit.bottom()));
                v.put("zoneTop", r(hit.top()));
                v.put("zoneTouches", hit.touches());
                return new GateResult(Gate.LOCATION, Verdict.PASS, "τ = " + f(tau) + "；有效区 " + zones.size() + " 个；当日最低 "
                        + f(low[t]) + " ≤ 区顶 " + f(hit.top()) + " 且收盘 " + f(close[t]) + " ≥ 区底 " + f(hit.bottom())
                        + "（区间 " + zone(hit) + "，" + hit.touches() + " 次触及）", v);
            }
            StringBuilder s = new StringBuilder("τ = ").append(f(tau)).append("；有效区 ").append(zones.size())
                    .append(" 个；当日最低 ").append(f(low[t])).append(" 未进入任何区");
            zones.stream().filter(z -> z.top() < low[t]).max(Comparator.comparingDouble(PriceZone::top)).ifPresent(z -> {
                v.put("nearestBelowTop", r(z.top()));
                s.append("；最近支撑区 ").append(zone(z)).append("（").append(z.touches()).append(" 次触及）在下方 ")
                        .append(pct(1 - z.top() / close[t], false));
            });
            return new GateResult(Gate.LOCATION, Verdict.FAIL, s.toString(), v);
        }

        private GateResult trigger() {
            double avg = Indicators.priorAverageVolume(volume, t, th.rvolLookback());
            double rvol = Double.isNaN(avg) ? Double.NaN : volume[t] / avg;
            Map<String, Object> v = values("volume", volume[t], "avgVolume20", avg, "rvol", rvol);
            v.put("open", r(open[t]));
            v.put("close", r(close[t]));
            if (Double.isNaN(rvol)) {
                return new GateResult(Gate.TRIGGER, Verdict.UNAVAILABLE, "前 " + th.rvolLookback() + " 日均量不足或为 0", v);
            }
            boolean volOk = rvol >= th.rvolThreshold();
            boolean up = close[t] > open[t];
            String criteria = "RVOL = " + f0(volume[t]) + " ÷ " + f0(avg) + " = " + f2(rvol) + (volOk ? " ≥ " : " < ")
                    + th.rvolThreshold() + "；收盘 " + f(close[t]) + (up ? " > " : " ≤ ") + "开盘 " + f(open[t]);
            return new GateResult(Gate.TRIGGER, volOk && up ? Verdict.PASS : Verdict.FAIL, criteria, v);
        }

        private GateResult risk(double atrT, PriceZone hit) {
            Map<String, Object> v = values("close", close[t], "atr14", atrT);
            if (Double.isNaN(atrT)) {
                return new GateResult(Gate.RISK, Verdict.UNAVAILABLE, "ATR14 样本不足", v);
            }
            double atrLeg = close[t] - th.stopAtrMultiple() * atrT;
            Double zoneLeg = hit == null ? null : hit.bottom() - th.zoneStopAtrMultiple() * atrT;
            double stop = zoneLeg == null ? atrLeg : Math.min(atrLeg, zoneLeg);
            String leg = zoneLeg != null && zoneLeg < atrLeg ? "区底腿" : "ATR 腿";
            double distance = (close[t] - stop) / close[t];
            v.put("atrLeg", r(atrLeg));
            v.put("zoneLeg", zoneLeg == null ? null : r(zoneLeg));
            v.put("stop", r(stop));
            v.put("stopLeg", zoneLeg != null && zoneLeg < atrLeg ? "ZONE" : "ATR");
            v.put("stopDistance", r(distance));
            boolean pass = stop > 0 && distance <= th.maxStopDistance();
            String stopFormula = zoneLeg != null && zoneLeg < atrLeg
                    ? f(hit.bottom()) + " − " + th.zoneStopAtrMultiple() + "×" + f(atrT)
                    : f(close[t]) + " − " + th.stopAtrMultiple() + "×" + f(atrT);
            String criteria = "止损 " + stopFormula + " = " + f(stop) + "（" + leg + "）；距离 " + pct(distance, false)
                    + (distance <= th.maxStopDistance() ? " ≤ " : " > ") + pct(th.maxStopDistance(), false);
            return new GateResult(Gate.RISK, pass ? Verdict.PASS : Verdict.FAIL, criteria, v);
        }

        private ExitPlan exitPlan(double atrT, GateResult risk) {
            if (risk.verdict() == Verdict.UNAVAILABLE) {
                return null;
            }
            double stop = (double) risk.values().get("stop");
            double riskPerShare = close[t] - stop;
            double highest = Double.NEGATIVE_INFINITY;
            for (int i = Math.max(0, t - th.chandelierPeriod() + 1); i <= t; i++) {
                highest = Math.max(highest, high[i]);
            }
            double chandelier = highest - th.chandelierAtrMultiple() * atrT;
            int from = Math.max(0, t - th.zoneLookback());
            PriceZone target = Fractals.cluster(Fractals.highs(high, th.fractalSide(), from), high, date,
                            th.zoneToleranceAtr() * atrT, th.minTouches()).stream()
                    .filter(z -> z.bottom() > close[t])
                    .min(Comparator.comparingDouble(PriceZone::bottom))
                    .orElse(null);
            Double targetPrice = target == null ? null : r(target.bottom());
            Double rewardRisk = target == null || riskPerShare <= 0 ? null : r((target.bottom() - close[t]) / riskPerShare);
            return new ExitPlan(r(stop), r(riskPerShare), r(close[t] + riskPerShare), r(chandelier),
                    th.timeStopDays(), target, targetPrice, rewardRisk);
        }

        private Map<String, Object> bonus(double tau, PriceZone hit) {
            Map<String, Object> b = new LinkedHashMap<>();
            // 52 周高低取最近 252 根（含判定日）；只是加分项，不影响判定
            int from = Math.max(0, t - th.zoneLookback() + 1);
            double hi = Double.NEGATIVE_INFINITY;
            double lo = Double.POSITIVE_INFINITY;
            for (int i = from; i <= t; i++) {
                hi = Math.max(hi, high[i]);
                lo = Math.min(lo, low[i]);
            }
            List<Double> levels = new ArrayList<>();
            boolean confluence = false;
            for (double level : th.fibonacciLevels()) {
                double price = hi - level * (hi - lo);
                levels.add(r(price));
                confluence |= hit != null && !Double.isNaN(tau) && hit.contains(price, tau);
            }
            b.put("fib52wHigh", r(hi));
            b.put("fib52wLow", r(lo));
            b.put("fibLevels", levels);
            b.put("fibConfluence", confluence);
            b.put("macdPositive", !Double.isNaN(macd[t]) && macd[t] > 0);
            return b;
        }
    }

    /**
     * 均线参与的"严格大于"：差值在相对 1e-9 以内视为相等。均线是两百个价格的和，数学上相等的两个值在 double 里会差一个噪声，
     * 结论随价格口径（判定日口径 / 今天的拆股口径）翻转。实测 WMT 2021-11-04 的 SMA200 与 20 日前精确相等（141.12535），
     * futu-trader 按今天口径的六位小数价格算成"大于"判了通过。
     */
    static boolean greater(double a, double b) {
        return a - b > 1e-9 * Math.max(Math.abs(a), Math.abs(b));
    }

    private static Map<String, Object> values(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], r((double) kv[i + 1]));
        }
        return m;
    }

    /** 落库数值保留 6 位小数；NaN 记为 null。 */
    static Double r(double x) {
        return Double.isNaN(x) || Double.isInfinite(x) ? null
                : BigDecimal.valueOf(x).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    private static String f(double x) {
        return BigDecimal.valueOf(x).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String f2(double x) {
        return BigDecimal.valueOf(x).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String f0(double x) {
        return BigDecimal.valueOf(x).setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static String pct(double ratio, boolean signed) {
        String s = BigDecimal.valueOf(ratio * 100).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
        return signed && ratio >= 0 ? "+" + s : s;
    }

    private static String zone(PriceZone z) {
        return "[" + f(z.bottom()) + ", " + f(z.top()) + "]";
    }
}
