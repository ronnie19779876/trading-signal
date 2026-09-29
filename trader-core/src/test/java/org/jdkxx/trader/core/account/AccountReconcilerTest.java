package org.jdkxx.trader.core.account;

import org.jdkxx.trader.core.account.AccountReconciler.Check;
import org.jdkxx.trader.core.account.AccountReconciler.Result;
import org.jdkxx.trader.core.account.AccountReconciler.Status;
import org.jdkxx.trader.core.account.ValuedPosition.PriceSource;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Position;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 数据全是虚构的。 */
class AccountReconcilerTest {

    private static final BigDecimal TOL = new BigDecimal("0.002");
    private static final BigDecimal ONE_USD = BigDecimal.ONE;

    private static Position pos(String symbol, String type, String qty) {
        return new Position(Broker.IBKR, "ACCT-A", symbol + "-ref", symbol, symbol, type, "NYSE", null, "USD", new BigDecimal(qty), null);
    }

    private static ValuedPosition stock(String symbol, Long id, String qty, String price) {
        return new ValuedPosition(pos(symbol, "STK", qty), id, price == null ? null : new BigDecimal(price),
                price == null ? PriceSource.NONE : PriceSource.BAR, false);
    }

    private static ValuedPosition cash(String symbol, String qty, String price) {
        return new ValuedPosition(pos(symbol, "STK", qty), null, new BigDecimal(price), PriceSource.SNAPSHOT, true);
    }

    private static BigDecimal dec(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    private static AccountSummary summary(String net, String cash, String stock, String div) {
        return summary(net, cash, stock, div, "0");
    }

    private static AccountSummary summary(String net, String cash, String stock, String div, String interest) {
        return new AccountSummary(Broker.IBKR, "ACCT-A", Instant.parse("2026-09-14T22:00:00Z"), "USD", dec(net), dec(cash), dec(stock),
                null, null, null, null, null, null, dec(div), dec(interest), Map.of());
    }

    private static Check check(Result r, String name) {
        return r.checks().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void 全部一致时为OK_现金管理工具计入市值但不参与持仓核对() {
        Result r = AccountReconciler.reconcile(summary("1150", "50", "1100", "0"),
                List.of(stock("AAA", 1L, "10", "100"), cash("CASHX", "100", "1")),
                Map.of(1L, "AAA"), Set.of(), TOL, ONE_USD);

        assertThat(r.status()).isEqualTo(Status.OK);
        assertThat(r.positionValue()).isEqualByComparingTo("1100");
        assertThat(r.checks()).extracting(Check::status).containsOnly(Status.OK);
        assertThat(check(r, "holdings").detail()).contains("现金管理工具 1 条不参与");
        assertThat(r.brief()).isEqualTo("四项全部通过");
    }

    @Test
    void 恒等式按偏差分级() {
        assertThat(AccountReconciler.identity(summary("100002.4", "2", "100000", "0"), ONE_USD).status()).isEqualTo(Status.OK);
        assertThat(AccountReconciler.identity(summary("100003.5", "2", "100000", "0"), ONE_USD).status()).isEqualTo(Status.WARN);
        assertThat(AccountReconciler.identity(summary("101000", "2", "100000", "0"), ONE_USD).status()).isEqualTo(Status.FAIL);
    }

    /**
     * 要害：应计利息是恒等式的第四项。
     *
     * <p>数字取自 2026-09-28 生产快照（补拍那次）：
     * 25899.69 + 484142.45 + 379.49 + <b>1.49</b> = 510423.12，与盈透净值分毫不差。
     * 3.1.2 及以前只加前三项，少的正好是 {@code $LEDGER-AccruedCash} 那 1.49——
     * 对账误判 WARN、快照作业记 PARTIAL、{@code jobs} 健康降级，而数据本身没有任何问题。
     * 此前 8 次快照全过，只因现金余额小、应计利息一直是 0。
     */
    @Test
    void 恒等式含应计利息_用09_28生产真实数字() {
        Check c = AccountReconciler.identity(
                summary("510423.12", "25899.69", "484142.45", "379.49", "1.49"), ONE_USD);

        assertThat(c.status()).as("四项相加正好等于净值").isEqualTo(Status.OK);
        assertThat(c.detail()).contains("应计利息 1.49").contains("相差 0.00");

        // 同一组数字，漏掉应计利息就会误报（3.1.2 及以前的行为）
        assertThat(AccountReconciler.identity(
                summary("510423.12", "25899.69", "484142.45", "379.49", "0"), ONE_USD).status())
                .as("少算这一项就会误判").isEqualTo(Status.WARN);
    }

    /**
     * 缺项按 0 计、继续核对，而不是整条弃核（3.1.3 改）。
     * 盈透对不同账户类型返回的标签不同，少一项就放弃会让这条关键检查在一部分账户上永远失效；
     * detail 里注明哪几项是按 0 计的。净值缺了才真的没法算。
     */
    @Test
    void 缺项按0计并在文案里注明() {
        Check c = AccountReconciler.identity(summary("100002", "2", "100000", null, null), ONE_USD);
        assertThat(c.status()).isEqualTo(Status.OK);
        assertThat(c.detail()).contains("按 0 计").contains("应计股息").contains("应计利息");

        Check noNet = AccountReconciler.identity(summary(null, "2", "100000", "0"), ONE_USD);
        assertThat(noNet.status()).as("净值缺了才真的没法核对").isEqualTo(Status.WARN);
        assertThat(noNet.detail()).contains("没有净值");
    }

    @Test
    void 市值偏差按容差分级() {
        List<ValuedPosition> ps = List.of(stock("AAA", 1L, "10", "100"));
        BigDecimal ours = new BigDecimal("1000");

        assertThat(AccountReconciler.marketValue(summary(null, null, "1001", null), ps, ours, TOL).status()).isEqualTo(Status.OK);
        assertThat(AccountReconciler.marketValue(summary(null, null, "1010", null), ps, ours, TOL).status()).isEqualTo(Status.WARN);
        assertThat(AccountReconciler.marketValue(summary(null, null, "1100", null), ps, ours, TOL).status()).isEqualTo(Status.FAIL);
    }

    @Test
    void 有持仓缺价时市值对账WARN并列出代码() {
        Result r = AccountReconciler.reconcile(summary("1550", "50", "1500", "0"),
                List.of(stock("AAA", 1L, "10", "100"), stock("BBB", 2L, "5", null)),
                Map.of(1L, "AAA", 2L, "BBB"), Set.of(), TOL, ONE_USD);

        assertThat(check(r, "marketValue").status()).isEqualTo(Status.WARN);
        assertThat(check(r, "marketValue").detail()).contains("BBB").contains("估值不完整");
        assertThat(r.status()).isEqualTo(Status.WARN);
    }

    @Test
    void 持仓与HOLDING不一致时WARN并分三类列出() {
        Check c = AccountReconciler.holdings(List.of(
                stock("AAA", 1L, "1", "1"),
                stock("BBB", 2L, "1", "1"),
                stock("CCC", null, "1", "1"),
                cash("CASHX", "1", "1")), Map.of(1L, "AAA", 3L, "DDD"), Set.of());

        assertThat(c.status()).isEqualTo(Status.WARN);
        assertThat(c.detail()).contains("持有但池里没标 HOLDING [BBB]")
                .contains("池里标了 HOLDING 但已不持有 [DDD]")
                .contains("库里没有的持仓 [CCC]")
                .doesNotContain("CASHX [");
    }

    @Test
    void 持有的基准不要求标HOLDING() {
        // 标普基准用的就是持仓里的 SPY 这类标的：角色是 BENCHMARK，持有它不算"没标 HOLDING"
        Check c = AccountReconciler.holdings(List.of(stock("AAA", 1L, "1", "1"), stock("IDX", 7L, "1", "1")),
                Map.of(1L, "AAA"), Set.of(7L));

        assertThat(c.status()).isEqualTo(Status.OK);
        assertThat(c.detail()).contains("持有的基准 1 只保留基准角色");
    }

    @Test
    void 非股票持仓WARN且不计入本系统市值() {
        ValuedPosition option = new ValuedPosition(pos("AAA 261218C00100000", "OPT", "1"), null, new BigDecimal("5"), PriceSource.BAR, false);
        Result r = AccountReconciler.reconcile(summary("1050", "50", "1000", "0"),
                List.of(stock("AAA", 1L, "10", "100"), option), Map.of(1L, "AAA"), Set.of(), TOL, ONE_USD);

        assertThat(check(r, "otherAssets").status()).isEqualTo(Status.WARN);
        assertThat(r.positionValue()).isEqualByComparingTo("1000");
        assertThat(check(r, "marketValue").status()).isEqualTo(Status.OK);
    }

    @Test
    void 总状态取最差() {
        Result r = AccountReconciler.reconcile(summary("9999", "50", "1000", "0"),
                List.of(stock("AAA", 1L, "10", "100")), Map.of(1L, "AAA"), Set.of(), TOL, ONE_USD);

        assertThat(check(r, "identity").status()).isEqualTo(Status.FAIL);
        assertThat(r.status()).isEqualTo(Status.FAIL);
        assertThat(r.brief()).startsWith("identity FAIL");
    }
}
