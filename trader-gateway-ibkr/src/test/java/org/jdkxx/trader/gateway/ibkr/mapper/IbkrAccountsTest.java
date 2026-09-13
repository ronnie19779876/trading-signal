package org.jdkxx.trader.gateway.ibkr.mapper;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts.PositionRow;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts.SummaryRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 数据全是虚构的：账户号不能长得像真账户号（扫描器会拦），也不放真实持仓。 */
class IbkrAccountsTest {

    private static final String ACCT = "ACCT-A";
    private static final Instant NOW = Instant.parse("2026-09-14T22:00:00Z");

    private static Contract stock(String symbol, int conId, String exchange) {
        Contract c = new Contract();
        c.symbol(symbol);
        c.localSymbol(symbol);
        c.secType("STK");
        c.exchange(exchange);
        c.currency("USD");
        c.conid(conId);
        return c;
    }

    private static SummaryRow tag(String tag, String value) {
        return new SummaryRow(ACCT, tag, value, "USD");
    }

    private static List<SummaryRow> ledger() {
        return List.of(
                new SummaryRow(ACCT, "AccountType", "INDIVIDUAL", ""),
                tag("NetLiquidation", "10035.65"),
                tag("TotalCashValue", "1000.00"),
                tag("GrossPositionValue", "9000.00"),
                tag("AvailableFunds", "5000.00"),
                tag("BuyingPower", "20000.00"),
                tag("ExcessLiquidity", "5000.00"),
                tag("$LEDGER-Currency", "USD"),
                tag("$LEDGER-StockMarketValue", "9000.00"),
                tag("$LEDGER-UnrealizedPnL", "123.45"),
                tag("$LEDGER-RealizedPnL", "0.00"),
                tag("$LEDGER-NetDividend", "35.65"),
                tag("$LEDGER-AccountOrGroup", ACCT),
                tag("$LEDGER-Cryptocurrency", ""));
    }

    @Test
    void 持仓按券商原样映射_类别股代码保留空格_碎股数量不丢精度() {
        List<Position> ps = IbkrAccounts.positions(ACCT, List.of(
                new PositionRow(ACCT, stock("XYZ B", 1001, "NYSE"), Decimal.get(3), 400.5),
                new PositionRow(ACCT, stock("ABC", 1002, "NASDAQ"), Decimal.parse("0.5"), 20.25)));

        assertThat(ps).hasSize(2);
        Position first = ps.get(0);
        assertThat(first.symbol()).isEqualTo("XYZ B");
        assertThat(first.brokerRef()).isEqualTo("1001");
        assertThat(first.securityType()).isEqualTo("STK");
        assertThat(first.exchange()).isEqualTo("NYSE");
        assertThat(first.primaryExchange()).isNull();
        assertThat(first.currency()).isEqualTo("USD");
        assertThat(first.quantity()).isEqualByComparingTo("3");
        assertThat(first.averageCost()).isEqualByComparingTo("400.5");
        assertThat(ps.get(1).quantity()).isEqualByComparingTo("0.5");
    }

    @Test
    void 券商未给成本价时为null() {
        List<Position> ps = IbkrAccounts.positions(ACCT, List.of(
                new PositionRow(ACCT, stock("ABC", 1002, "NASDAQ"), Decimal.get(1), Double.MAX_VALUE),
                new PositionRow(ACCT, stock("DEF", 1003, "NASDAQ"), Decimal.get(1), Double.NaN)));

        assertThat(ps).extracting(Position::averageCost).containsOnlyNulls();
    }

    @Test
    void 只保留目标账户的持仓() {
        List<Position> ps = IbkrAccounts.positions(ACCT, List.of(
                new PositionRow(ACCT, stock("ABC", 1002, "NASDAQ"), Decimal.get(1), 1.0),
                new PositionRow("ACCT-B", stock("DEF", 1003, "NASDAQ"), Decimal.get(1), 1.0)));

        assertThat(ps).extracting(Position::symbol).containsExactly("ABC");
    }

    @Test
    void 数量为零的当天清仓条目原样保留() {
        List<Position> ps = IbkrAccounts.positions(ACCT, List.of(
                new PositionRow(ACCT, stock("ABC", 1002, "NASDAQ"), Decimal.ZERO, 1.0)));

        assertThat(ps).singleElement().satisfies(p -> assertThat(p.quantity()).isEqualByComparingTo("0"));
    }

    @Test
    void 数量无效时报错而不是记成零() {
        // 记成 0 会被当成清仓，进而把标的移出池——比失败更糟
        assertThatThrownBy(() -> IbkrAccounts.positions(ACCT, List.of(
                new PositionRow(ACCT, stock("ABC", 1002, "NASDAQ"), Decimal.INVALID, 1.0))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ABC");
    }

    @Test
    void 汇总取常规标签与美元分类账_恒等式成立() {
        AccountSummary s = IbkrAccounts.summary(ACCT, ledger(), NOW);

        assertThat(s.currency()).isEqualTo("USD");
        assertThat(s.receivedAt()).isEqualTo(NOW);
        assertThat(s.netLiquidation()).isEqualByComparingTo("10035.65");
        assertThat(s.totalCash()).isEqualByComparingTo("1000.00");
        assertThat(s.stockMarketValue()).isEqualByComparingTo("9000.00");
        assertThat(s.grossPositionValue()).isEqualByComparingTo("9000.00");
        assertThat(s.availableFunds()).isEqualByComparingTo("5000.00");
        assertThat(s.buyingPower()).isEqualByComparingTo("20000.00");
        assertThat(s.excessLiquidity()).isEqualByComparingTo("5000.00");
        assertThat(s.unrealizedPnl()).isEqualByComparingTo("123.45");
        assertThat(s.realizedPnl()).isEqualByComparingTo("0");
        assertThat(s.accruedDividend()).isEqualByComparingTo("35.65");
        assertThat(s.totalCash().add(s.stockMarketValue()).add(s.accruedDividend())).isEqualByComparingTo(s.netLiquidation());
    }

    @Test
    void 账户号不进raw也不进toString() {
        AccountSummary s = IbkrAccounts.summary(ACCT, ledger(), NOW);

        assertThat(s.raw()).doesNotContainKey("$LEDGER-AccountOrGroup").containsEntry("AccountType", "INDIVIDUAL");
        assertThat(s.raw().values()).noneMatch(v -> v.contains(ACCT));
        assertThat(s.toString()).doesNotContain(ACCT);
        assertThat(new SummaryRow(ACCT, "NetLiquidation", "1", "USD").toString()).doesNotContain(ACCT);
        assertThat(new PositionRow(ACCT, stock("ABC", 1002, "NASDAQ"), Decimal.get(1), 1.0).toString()).doesNotContain(ACCT);
        assertThat(IbkrAccounts.positions(ACCT, List.of(new PositionRow(ACCT, stock("ABC", 1002, "NASDAQ"), Decimal.get(1), 1.0)))
                .get(0).toString()).doesNotContain(ACCT);
    }

    @Test
    void 缺失与非数字的标签为null() {
        AccountSummary s = IbkrAccounts.summary(ACCT, List.of(tag("NetLiquidation", "n/a"), tag("TotalCashValue", "")), NOW);

        assertThat(s.netLiquidation()).isNull();
        assertThat(s.totalCash()).isNull();
        assertThat(s.accruedDividend()).isNull();
    }

    @Test
    void 没有目标账户数据时报错() {
        assertThatThrownBy(() -> IbkrAccounts.summary(ACCT, List.of(new SummaryRow("ACCT-B", "NetLiquidation", "1", "USD")), NOW))
                .isInstanceOf(GatewayException.class)
                .hasMessageNotContaining(ACCT);
    }
}
