package org.jdkxx.trader.core.account;

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
import org.jdkxx.trader.domain.AccountSummary;

/**
 * Account snapshot reconciliation (pure computation). Four checks; the overall status is the worst
 * of them:
 *
 * <ol>
 *   <li>{@code identity} funds identity: cash + stock market value + accrued dividend + accrued
 *       interest = net liquidation (measured at IBKR to hold to the cent);
 *   <li>{@code marketValue} market value: sum of quantity x this system's closing price versus
 *       IBKR's stock market value (measured differences are on the order of 1e-5);
 *   <li>{@code holdings} position set: the stocks held match the pool's HOLDING role (benchmarks
 *       and cash-management instruments excluded);
 *   <li>{@code otherAssets} non-stock positions: not valued; reported if present.
 * </ol>
 *
 * All money math is BigDecimal; ratios use {@link MathContext#DECIMAL64}.
 */
public final class AccountReconciler {

  public enum Status {
    OK,
    WARN,
    FAIL;

    Status worse(Status other) {
      return other.ordinal() > ordinal() ? other : this;
    }
  }

  public record Check(String name, Status status, String detail) {}

  public record Result(Status status, BigDecimal positionValue, List<Check> checks) {

    /** The checks that did not pass, for the job summary. */
    public String brief() {
      String s =
          checks.stream()
              .filter(c -> c.status() != Status.OK)
              .map(c -> c.name() + " " + c.status() + "：" + c.detail())
              .collect(Collectors.joining("；"));
      return s.isEmpty() ? "四项全部通过" : s;
    }
  }

  static final BigDecimal IDENTITY_WARN_RATIO = new BigDecimal("0.001");

  private AccountReconciler() {}

  /**
   * @param holdings instruments with the HOLDING role in the pool: id -&gt; symbol
   * @param benchmarks ids of instruments with the BENCHMARK role in the pool: holding them does not
   *     require HOLDING
   */
  public static Result reconcile(
      AccountSummary summary,
      List<ValuedPosition> positions,
      Map<Long, String> holdings,
      Set<Long> benchmarks,
      BigDecimal valueTolerance,
      BigDecimal identityTolerance) {
    List<ValuedPosition> stocks = positions.stream().filter(ValuedPosition::stock).toList();
    List<ValuedPosition> others = positions.stream().filter(p -> !p.stock()).toList();
    BigDecimal ours =
        stocks.stream()
            .map(ValuedPosition::marketValue)
            .filter(Objects::nonNull)
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
   * Funds identity. <b>Accrued interest (IBKR {@code $LEDGER-AccruedCash}) is the fourth term,
   * added in 3.1.3</b>: while the cash balance was small it was always 0, the three-term sum held
   * exactly and 8 consecutive snapshots were OK; on 2026-09-28, after cash grew past twenty
   * thousand, it became 1.49 and the identity was off by exactly that term. The result was a false
   * WARN, a PARTIAL job and a degraded {@code jobs} health, while the data itself was perfectly
   * correct.
   *
   * <p>The detail prints all four numbers: with only a difference, locating that 1.49 means digging
   * through the 31 tags in {@code raw}.
   *
   * <p>A missing term counts as 0 instead of dropping the whole check: IBKR returns different tags
   * for different account types, and giving up whenever one term is missing would permanently
   * disable this critical check for some accounts. Only a missing net liquidation really makes it
   * impossible.
   *
   * <p>Status: OK within the absolute tolerance (USD); beyond it WARN while the gap is at most
   * {@link #IDENTITY_WARN_RATIO} of net liquidation, otherwise FAIL.
   *
   * <p><b>Known limit</b>: the IBKR ledger has 13 more asset classes (bonds, options, funds,
   * money-market funds, FX cash, TBill/TBond, warrants, crypto), all 0.00 on this account today. As
   * soon as any of them appears, this identity will miss a term the same way and report falsely;
   * then either add it the same way or switch to summing the ledger.
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
    String detail =
        "现金 "
            + cash
            + " + 股票市值 "
            + stock
            + " + 应计股息 "
            + dividend
            + " + 应计利息 "
            + interest
            + " = "
            + sum
            + "，与净值 "
            + s.netLiquidation()
            + " 相差 "
            + gap.setScale(2, RoundingMode.HALF_UP)
            + (missing.isEmpty() ? "" : "（汇总里没有 " + String.join("、", missing) + "，按 0 计）");
    if (gap.compareTo(tolerance) <= 0) {
      return new Check("identity", Status.OK, detail);
    }
    BigDecimal ratio = ratio(gap, s.netLiquidation());
    return new Check(
        "identity",
        ratio.compareTo(IDENTITY_WARN_RATIO) <= 0 ? Status.WARN : Status.FAIL,
        detail + "（占净值 " + pct(ratio) + "）");
  }

  private static BigDecimal zeroIfNull(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  static Check marketValue(
      AccountSummary s, List<ValuedPosition> stocks, BigDecimal ours, BigDecimal tolerance) {
    if (s.stockMarketValue() == null) {
      return new Check("marketValue", Status.WARN, "资金汇总没有股票市值，无法对账");
    }
    BigDecimal ratio = ratio(ours.subtract(s.stockMarketValue()).abs(), s.stockMarketValue());
    String detail = "按收盘价估值与盈透股票市值相差 " + pct(ratio);
    List<String> unpriced =
        stocks.stream()
            .filter(p -> p.price() == null)
            .map(ValuedPosition::symbol)
            .sorted()
            .toList();
    if (!unpriced.isEmpty()) {
      return new Check(
          "marketValue",
          Status.WARN,
          unpriced.size() + " 条持仓缺价 " + unpriced + "，" + detail + "（估值不完整）");
    }
    if (ratio.compareTo(tolerance) <= 0) {
      return new Check("marketValue", Status.OK, detail);
    }
    return new Check(
        "marketValue",
        ratio.compareTo(tolerance.multiply(BigDecimal.valueOf(5))) <= 0 ? Status.WARN : Status.FAIL,
        detail);
  }

  static Check holdings(
      List<ValuedPosition> stocks, Map<Long, String> holdings, Set<Long> benchmarks) {
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
    List<String> notMarked =
        held.entrySet().stream()
            .filter(e -> !holdings.containsKey(e.getKey()))
            .map(Map.Entry::getValue)
            .sorted()
            .toList();
    List<String> stale =
        holdings.entrySet().stream()
            .filter(e -> !held.containsKey(e.getKey()))
            .map(Map.Entry::getValue)
            .sorted()
            .toList();
    String cashNote =
        (cash > 0 ? "（现金管理工具 " + cash + " 条不参与）" : "")
            + (heldBenchmarks > 0 ? "（持有的基准 " + heldBenchmarks + " 只保留基准角色）" : "");
    if (notMarked.isEmpty() && stale.isEmpty() && unmapped.isEmpty()) {
      return new Check(
          "holdings", Status.OK, "持有的 " + held.size() + " 只股票与池里的 HOLDING 一致" + cashNote);
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
    return new Check(
        "otherAssets",
        Status.WARN,
        "非股票持仓 "
            + others.size()
            + " 条不参与估值 "
            + others.stream()
                .map(p -> p.symbol() + "(" + p.position().securityType() + ")")
                .sorted()
                .toList());
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
