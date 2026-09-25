package org.jdkxx.trader.core.marketdata.valuation;

import org.jdkxx.trader.domain.valuation.SotpAssumptions;
import org.jdkxx.trader.domain.valuation.SotpBasis;
import org.jdkxx.trader.domain.valuation.SotpResult;
import org.jdkxx.trader.domain.valuation.SotpScenario;
import org.jdkxx.trader.domain.valuation.SotpSegment;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分部估值（SOTP）的纯计算：没有 Spring、不碰库、不调网关，给什么算什么。
 *
 * <p>结构是使用者给的：每条业务线 量 × 价 = 营收 → × 净利率 = 净利 → × 本益比 = 业务价值；
 * 目标年股价 =（Σ 业务价值 + 目标年净现金）÷ 目标年股数，再折回基准日。
 *
 * <p><b>这个类只做算术与一致性核对，不预测。</b>券商只给合并报表，没有分部量价、没有一致预期，
 * 所以每条业务线的假设都得使用者自己填；本类的价值在于把假设算清楚，并把自相矛盾的假设指出来
 * （净利率口径核对、单因子敏感度、反推）。
 */
public final class SotpCalculator {

    /** 隐含合并净利率与实际的偏离超过这个百分点就提示。经验阈值，不是行业标准。 */
    static final double MARGIN_TOLERANCE_PP = 10.0d;

    private SotpCalculator() {
    }

    public static SotpResult calculate(SotpAssumptions assumptions, SotpBasis basis) {
        if (assumptions == null || basis == null) {
            throw new IllegalArgumentException("缺假设或底座数据");
        }
        double horizon = assumptions.horizonYears();
        double factor = assumptions.discountFactor();

        Map<SotpScenario, SotpResult.ScenarioValue> scenarios = new EnumMap<>(SotpScenario.class);
        for (SotpScenario s : SotpScenario.values()) {
            scenarios.put(s, value(assumptions, basis, factor, segment -> segment.on(s)));
        }
        return new SotpResult(horizon, factor, Map.copyOf(scenarios),
                sensitivities(assumptions, basis, factor),
                marginCheck(assumptions, basis),
                reverse(assumptions, basis, horizon));
    }

    /** 按给定的「每条业务线取哪个情景」算一份结果。敏感度要混用情景，所以这里收的是选择器而不是情景。 */
    private static SotpResult.ScenarioValue value(SotpAssumptions a, SotpBasis basis, double factor,
                                                  java.util.function.Function<SotpSegment, SotpSegment.SegmentCase> pick) {
        Map<String, Double> values = new LinkedHashMap<>();
        double total = 0;
        for (SotpSegment segment : a.segments()) {
            double v = pick.apply(segment).value();
            values.put(segment.name(), v);
            total += v;
        }
        double equity = total + a.targetNetCash();
        double target = equity / a.targetShares();
        double present = target * factor;
        Double upside = basis.currentPrice() > 0 ? (present / basis.currentPrice() - 1.0d) * 100.0d : null;
        // 用 LinkedHashMap 包一层，别用 Map.copyOf——它丢掉插入顺序，而 ScenarioValue 的 javadoc
        // 明写「按输入顺序」；实际顺序会由 JVM 每次启动随机化的 SALT 决定（2026-09-25 全项目审查发现）
        return new SotpResult.ScenarioValue(java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values)),
                total, equity, target, present, upside);
    }

    /** 只动一条业务线（熊 ↔ 牛），其余保持基准。 */
    private static List<SotpResult.Sensitivity> sensitivities(SotpAssumptions a, SotpBasis basis, double factor) {
        List<SotpResult.Sensitivity> out = new ArrayList<>();
        for (SotpSegment moving : a.segments()) {
            double low = value(a, basis, factor,
                    s -> s == moving ? s.on(SotpScenario.BEAR) : s.on(SotpScenario.BASE)).presentValue();
            double high = value(a, basis, factor,
                    s -> s == moving ? s.on(SotpScenario.BULL) : s.on(SotpScenario.BASE)).presentValue();
            out.add(new SotpResult.Sensitivity(moving.name(), low, high, high - low));
        }
        out.sort((x, y) -> Double.compare(y.swing(), x.swing()));
        return List.copyOf(out);
    }

    /** 基准情景下 Σ 净利 ÷ Σ 营收，与最近一期实际合并净利率比。 */
    private static SotpResult.MarginCheck marginCheck(SotpAssumptions a, SotpBasis basis) {
        double revenue = 0;
        double netIncome = 0;
        for (SotpSegment segment : a.segments()) {
            SotpSegment.SegmentCase c = segment.on(SotpScenario.BASE);
            revenue += c.revenue();
            netIncome += c.netIncome();
        }
        double implied = revenue == 0 ? 0 : netIncome / revenue;
        Double actual = basis.actualNetMargin();
        if (actual == null) {
            return new SotpResult.MarginCheck(implied, null, null, true);
        }
        double deviationPp = (implied - actual) * 100.0d;
        return new SotpResult.MarginCheck(implied, actual, deviationPp, Math.abs(deviationPp) <= MARGIN_TOLERANCE_PP);
    }

    /** 反推现价已经押注了什么。缺本益比基准就不给结论——硬编一个倍数只会把猜测伪装成计算。 */
    private static SotpResult.Reverse reverse(SotpAssumptions a, SotpBasis basis, double horizon) {
        if (basis.baselinePe() == null || basis.baselinePe() <= 0 || basis.currentPrice() <= 0) {
            return null;
        }
        double requiredPrice = basis.currentPrice() * Math.pow(1.0d + a.discountRate(), horizon);
        double requiredCap = requiredPrice * a.targetShares();
        double requiredNetIncome = (requiredCap - a.targetNetCash()) / basis.baselinePe();
        Double cagr = null;
        Double current = basis.currentNetIncome();
        if (current != null && current > 0 && requiredNetIncome > 0 && horizon > 0) {
            cagr = Math.pow(requiredNetIncome / current, 1.0d / horizon) - 1.0d;
        }
        return new SotpResult.Reverse(requiredPrice, requiredCap, requiredNetIncome, cagr);
    }
}
