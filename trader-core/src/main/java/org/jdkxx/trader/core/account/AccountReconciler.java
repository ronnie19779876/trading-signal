package org.jdkxx.trader.core.account;

import org.jdkxx.trader.domain.AccountSummary;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 账户快照对账（纯计算）。四项，总状态取最差：
 * <ol>
 *   <li>{@code identity} 资金恒等式：现金 + 股票市值 + 应计股息 + 应计利息 = 净值（盈透实测精确到分）；</li>
 *   <li>{@code marketValue} 市值：Σ 数量 × 本系统收盘价 对比盈透股票市值（实测差十万分之一量级）；</li>
 *   <li>{@code holdings} 持仓集合：持有的股票与池里的 HOLDING 角色一致（基准与现金管理工具不参与）；</li>
 *   <li>{@code otherAssets} 非股票持仓：不参与估值，有就提示。</li>
 * </ol>
 */
public final class AccountReconciler {

    public enum Status {
        OK, WARN, FAIL;

        Status worse(Status other) {
            return other.ordinal() > ordinal() ? other : this;
        }
    }

    public record Check(String name, Status status, String detail) {
    }

    public record Result(Status status, BigDecimal positionValue, List<Check> checks) {

        /** 没通过的项，给作业摘要用。 */
        public String brief() {
            String s = checks.stream().filter(c -> c.status() != Status.OK)
                    .map(c -> c.name() + " " + c.status() + "：" + c.detail())
                    .collect(Collectors.joining("；"));
            return s.isEmpty() ? "四项全部通过" : s;
        }
    }

    static final BigDecimal IDENTITY_WARN_RATIO = new BigDecimal("0.001");

    private AccountReconciler() {
    }

    /**
     * @param holdings   池里 HOLDING 角色的标的：id → 代码
     * @param benchmarks 池里 BENCHMARK 角色的标的 id：持有它们不要求标 HOLDING
     */
    public static Result reconcile(AccountSummary summary, List<ValuedPosition> positions, Map<Long, String> holdings,
                                   Set<Long> benchmarks, BigDecimal valueTolerance, BigDecimal identityTolerance) {
        List<ValuedPosition> stocks = positions.stream().filter(ValuedPosition::stock).toList();
        List<ValuedPosition> others = positions.stream().filter(p -> !p.stock()).toList();
        BigDecimal ours = stocks.stream().map(ValuedPosition::marketValue).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Check> checks = new ArrayList<>();
        checks.add(identity(summary, identityTolerance));
        checks.add(marketValue(summary, stocks, ours, valueTolerance));
        checks.add(holdings(stocks, holdings, benchmarks));
        checks.add(otherAssets(others));
        Status worst = checks.stream().map(Check::status).reduce(Status.OK, Status::worse);
        return new Result(worst, ours.setScale(4, RoundingMode.HALF_UP), List.copyOf(checks));
    }

    /**
     * 资金恒等式。<b>应计利息（盈透 {@code $LEDGER-AccruedCash}）是 3.1.3 补上的第四项</b>：
     * 此前现金余额小、它一直是 0，三项相加正好成立，连续 8 次快照都判 OK；
     * 2026-09-28 现金涨到两万多之后它变成 1.49，等式差的就是这一项——当时误判成 WARN、
     * 作业记 PARTIAL、{@code jobs} 健康降级，而数据本身分毫不差。
     *
     * <p>detail 把四项数字都打出来：只给一个差额时，定位这 1.49 要去翻 {@code raw} 里的 31 个标签。
     *
     * <p>缺项按 0 处理而不是整条弃核：盈透对不同账户类型返回的标签不同，
     * 少一项就放弃核对会让这条关键检查在一部分账户上永远失效。净值缺了才真的没法算。
     *
     * <p><b>已知边界</b>：盈透分类账里还有 13 类其它资产（债券、期权、基金、货币基金、外汇现金、
     * TBill/TBond、认股权证、加密），本账户当前全为 0.00。账上一旦出现其中任意一类，
     * 这条等式会以同样的方式少算一项而误报——届时要么按同样办法补进来，要么改成按分类账求和。
     */
    static Check identity(AccountSummary s, BigDecimal tolerance) {
        if (s.netLiquidation() == null) {
            return new Check("identity", Status.WARN, "资金汇总没有净值，恒等式无法核对");
        }
        BigDecimal cash = zeroIfNull(s.totalCash());
        BigDecimal stock = zeroIfNull(s.stockMarketValue());
        BigDecimal dividend = zeroIfNull(s.accruedDividend());
        BigDecimal interest = zeroIfNull(s.accruedInterest());
        BigDecimal sum = cash.add(stock).add(dividend).add(interest);
        BigDecimal gap = sum.subtract(s.netLiquidation()).abs();

        List<String> missing = new ArrayList<>();
        if (s.totalCash() == null) {
            missing.add("现金");
        }
        if (s.stockMarketValue() == null) {
            missing.add("股票市值");
        }
        if (s.accruedDividend() == null) {
            missing.add("应计股息");
        }
        if (s.accruedInterest() == null) {
            missing.add("应计利息");
        }
        String detail = "现金 " + cash + " + 股票市值 " + stock + " + 应计股息 " + dividend
                + " + 应计利息 " + interest + " = " + sum
                + "，与净值 " + s.netLiquidation() + " 相差 " + gap.setScale(2, RoundingMode.HALF_UP)
                + (missing.isEmpty() ? "" : "（汇总里没有 " + String.join("、", missing) + "，按 0 计）");
        if (gap.compareTo(tolerance) <= 0) {
            return new Check("identity", Status.OK, detail);
        }
        BigDecimal ratio = ratio(gap, s.netLiquidation());
        return new Check("identity", ratio.compareTo(IDENTITY_WARN_RATIO) <= 0 ? Status.WARN : Status.FAIL,
                detail + "（占净值 " + pct(ratio) + "）");
    }

    private static BigDecimal zeroIfNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static Check marketValue(AccountSummary s, List<ValuedPosition> stocks, BigDecimal ours, BigDecimal tolerance) {
        if (s.stockMarketValue() == null) {
            return new Check("marketValue", Status.WARN, "资金汇总没有股票市值，无法对账");
        }
        BigDecimal ratio = ratio(ours.subtract(s.stockMarketValue()).abs(), s.stockMarketValue());
        String detail = "按收盘价估值与盈透股票市值相差 " + pct(ratio);
        List<String> unpriced = stocks.stream().filter(p -> p.price() == null).map(ValuedPosition::symbol).sorted().toList();
        if (!unpriced.isEmpty()) {
            return new Check("marketValue", Status.WARN,
                    unpriced.size() + " 条持仓缺价 " + unpriced + "，" + detail + "（估值不完整）");
        }
        if (ratio.compareTo(tolerance) <= 0) {
            return new Check("marketValue", Status.OK, detail);
        }
        return new Check("marketValue", ratio.compareTo(tolerance.multiply(BigDecimal.valueOf(5))) <= 0 ? Status.WARN : Status.FAIL, detail);
    }

    static Check holdings(List<ValuedPosition> stocks, Map<Long, String> holdings, Set<Long> benchmarks) {
        Map<Long, String> held = new LinkedHashMap<>();
        List<String> unmapped = new ArrayList<>();
        long cash = 0;
        long heldBenchmarks = 0;
        for (ValuedPosition p : stocks) {
            if (p.cashEquivalent()) {
                cash++;
            } else if (p.instrumentId() == null) {
                unmapped.add(p.symbol());
            } else if (benchmarks.contains(p.instrumentId())) {
                heldBenchmarks++;
            } else {
                held.put(p.instrumentId(), p.symbol());
            }
        }
        List<String> notMarked = held.entrySet().stream().filter(e -> !holdings.containsKey(e.getKey()))
                .map(Map.Entry::getValue).sorted().toList();
        List<String> stale = holdings.entrySet().stream().filter(e -> !held.containsKey(e.getKey()))
                .map(Map.Entry::getValue).sorted().toList();
        String cashNote = (cash > 0 ? "（现金管理工具 " + cash + " 条不参与）" : "")
                + (heldBenchmarks > 0 ? "（持有的基准 " + heldBenchmarks + " 只保留基准角色）" : "");
        if (notMarked.isEmpty() && stale.isEmpty() && unmapped.isEmpty()) {
            return new Check("holdings", Status.OK, "持有的 " + held.size() + " 只股票与池里的 HOLDING 一致" + cashNote);
        }
        List<String> parts = new ArrayList<>();
        if (!notMarked.isEmpty()) {
            parts.add("持有但池里没标 HOLDING " + notMarked);
        }
        if (!stale.isEmpty()) {
            parts.add("池里标了 HOLDING 但已不持有 " + stale);
        }
        if (!unmapped.isEmpty()) {
            parts.add("库里没有的持仓 " + unmapped.stream().sorted().toList());
        }
        return new Check("holdings", Status.WARN, String.join("；", parts) + cashNote);
    }

    static Check otherAssets(List<ValuedPosition> others) {
        if (others.isEmpty()) {
            return new Check("otherAssets", Status.OK, "没有非股票持仓");
        }
        return new Check("otherAssets", Status.WARN, "非股票持仓 " + others.size() + " 条不参与估值 "
                + others.stream().map(p -> p.symbol() + "(" + p.position().securityType() + ")").sorted().toList());
    }

    private static BigDecimal ratio(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return part.signum() == 0 ? BigDecimal.ZERO : BigDecimal.ONE;
        }
        return part.divide(whole.abs(), MathContext.DECIMAL64);
    }

    private static String pct(BigDecimal ratio) {
        return ratio.movePointRight(2).setScale(4, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
