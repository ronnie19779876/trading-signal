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
import java.util.stream.Collectors;

/**
 * 账户快照对账（纯计算）。四项，总状态取最差：
 * <ol>
 *   <li>{@code identity} 资金恒等式：现金 + 股票市值 + 应计股息 = 净值（盈透实测精确到分；只适用于纯股票账户）；</li>
 *   <li>{@code marketValue} 市值：Σ 数量 × 本系统收盘价 对比盈透股票市值（实测差十万分之一量级）；</li>
 *   <li>{@code holdings} 持仓集合：持有的股票与池里的 HOLDING 角色一致（现金管理工具不参与）；</li>
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
     * @param holdings 池里 HOLDING 角色的标的：id → 代码
     */
    public static Result reconcile(AccountSummary summary, List<ValuedPosition> positions, Map<Long, String> holdings,
                                   BigDecimal valueTolerance, BigDecimal identityTolerance) {
        List<ValuedPosition> stocks = positions.stream().filter(ValuedPosition::stock).toList();
        List<ValuedPosition> others = positions.stream().filter(p -> !p.stock()).toList();
        BigDecimal ours = stocks.stream().map(ValuedPosition::marketValue).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Check> checks = new ArrayList<>();
        checks.add(identity(summary, identityTolerance));
        checks.add(marketValue(summary, stocks, ours, valueTolerance));
        checks.add(holdings(stocks, holdings));
        checks.add(otherAssets(others));
        Status worst = checks.stream().map(Check::status).reduce(Status.OK, Status::worse);
        return new Result(worst, ours.setScale(4, RoundingMode.HALF_UP), List.copyOf(checks));
    }

    static Check identity(AccountSummary s, BigDecimal tolerance) {
        if (s.netLiquidation() == null || s.totalCash() == null || s.stockMarketValue() == null || s.accruedDividend() == null) {
            return new Check("identity", Status.WARN, "资金汇总缺字段（净值/现金/股票市值/应计股息），恒等式无法核对");
        }
        BigDecimal gap = s.totalCash().add(s.stockMarketValue()).add(s.accruedDividend()).subtract(s.netLiquidation()).abs();
        String detail = "现金 + 股票市值 + 应计股息 与净值相差 " + gap.setScale(2, RoundingMode.HALF_UP);
        if (gap.compareTo(tolerance) <= 0) {
            return new Check("identity", Status.OK, detail);
        }
        BigDecimal ratio = ratio(gap, s.netLiquidation());
        return new Check("identity", ratio.compareTo(IDENTITY_WARN_RATIO) <= 0 ? Status.WARN : Status.FAIL,
                detail + "（占净值 " + pct(ratio) + "）");
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

    static Check holdings(List<ValuedPosition> stocks, Map<Long, String> holdings) {
        Map<Long, String> held = new LinkedHashMap<>();
        List<String> unmapped = new ArrayList<>();
        long cash = 0;
        for (ValuedPosition p : stocks) {
            if (p.cashEquivalent()) {
                cash++;
            } else if (p.instrumentId() == null) {
                unmapped.add(p.symbol());
            } else {
                held.put(p.instrumentId(), p.symbol());
            }
        }
        List<String> notMarked = held.entrySet().stream().filter(e -> !holdings.containsKey(e.getKey()))
                .map(Map.Entry::getValue).sorted().toList();
        List<String> stale = holdings.entrySet().stream().filter(e -> !held.containsKey(e.getKey()))
                .map(Map.Entry::getValue).sorted().toList();
        String cashNote = cash > 0 ? "（现金管理工具 " + cash + " 条不参与）" : "";
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
