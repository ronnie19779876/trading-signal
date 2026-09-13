package org.jdkxx.trader.gateway.ibkr.mapper;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.gateway.GatewayException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TWS 持仓 / 账户汇总回调 → 领域对象。
 *
 * <p>实测（TWS API 10.30.01，2026-09-14 对真实网关只读探针）：
 * <ul>
 *   <li>{@code positionMulti} 的 primaryExch 为 null、exchange 有值；类别股代码带空格（{@code BRK B}）；数量可为小数；</li>
 *   <li>{@code accountSummary} 的 {@code $LEDGER-AccountOrGroup} 值就是明文账户号——必须剔除；</li>
 *   <li>{@code SettledCash} 在该账户类型下不返回，所以没有请求它；</li>
 *   <li>现金 + 股票市值 + 应计股息 与净值精确相等。</li>
 * </ul>
 */
public final class IbkrAccounts {

    /** 请求的标签：常规资金项 + 美元分类账（股票市值、未实现/已实现盈亏、应计股息只在 $LEDGER 里有）。 */
    public static final String SUMMARY_TAGS =
            "AccountType,NetLiquidation,TotalCashValue,GrossPositionValue,AvailableFunds,BuyingPower,ExcessLiquidity,$LEDGER:USD";

    static final String ACCOUNT_OR_GROUP = "$LEDGER-AccountOrGroup";

    /** positionMulti 回调的原样数据：泵线程上只打包，映射放到 dispatch 线程。 */
    public record PositionRow(String account, Contract contract, Decimal position, double averageCost) {

        @Override
        public String toString() {
            return "PositionRow[**** " + (contract == null ? null : contract.symbol()) + " " + position + "]";
        }
    }

    /** accountSummary 回调的原样数据。 */
    public record SummaryRow(String account, String tag, String value, String currency) {

        @Override
        public String toString() {
            return "SummaryRow[**** " + tag + "]";
        }
    }

    private IbkrAccounts() {
    }

    public static List<Position> positions(String accountId, List<PositionRow> rows) {
        List<Position> out = new ArrayList<>();
        for (PositionRow r : rows) {
            if (!accountId.equals(r.account()) || r.contract() == null) {
                continue;
            }
            Contract c = r.contract();
            out.add(new Position(Broker.IBKR, accountId, Integer.toString(c.conid()), c.symbol(), c.localSymbol(),
                    c.getSecType(), c.exchange(), c.primaryExch(), c.currency(), quantity(r.position(), c), cost(r.averageCost())));
        }
        return List.copyOf(out);
    }

    public static AccountSummary summary(String accountId, List<SummaryRow> rows, Instant receivedAt) {
        Map<String, String> raw = new LinkedHashMap<>();
        String currency = null;
        for (SummaryRow r : rows) {
            if (!accountId.equals(r.account()) || r.tag() == null) {
                continue;
            }
            String value = r.value() == null ? "" : r.value().trim();
            if (ACCOUNT_OR_GROUP.equals(r.tag()) || value.contains(accountId)) {
                continue;   // 值里带账户号的一律不留
            }
            raw.put(r.tag(), value);
            if ("NetLiquidation".equals(r.tag()) && r.currency() != null && !r.currency().isBlank()) {
                currency = r.currency().trim();
            }
        }
        if (raw.isEmpty()) {
            throw new GatewayException(Broker.IBKR, 0, "账户汇总里没有目标账户的数据（账户不在受管列表？）", false);
        }
        return new AccountSummary(Broker.IBKR, accountId, receivedAt, currency,
                number(raw, "NetLiquidation"),
                number(raw, "TotalCashValue"),
                number(raw, "$LEDGER-StockMarketValue"),
                number(raw, "GrossPositionValue"),
                number(raw, "AvailableFunds"),
                number(raw, "BuyingPower"),
                number(raw, "ExcessLiquidity"),
                number(raw, "$LEDGER-UnrealizedPnL"),
                number(raw, "$LEDGER-RealizedPnL"),
                number(raw, "$LEDGER-NetDividend"),
                raw);
    }

    /** 数量无效时报错：记成 0 会被当成清仓，比失败更糟。 */
    static BigDecimal quantity(Decimal d, Contract c) {
        if (d == null || !d.isValid()) {
            throw new IllegalStateException("持仓数量无效：" + c.symbol() + " conId=" + c.conid());
        }
        return d.value();
    }

    /** TWS 用 Double.MAX_VALUE 表示"未设"。 */
    static BigDecimal cost(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) || v == Double.MAX_VALUE ? null : BigDecimal.valueOf(v);
    }

    static BigDecimal number(Map<String, String> raw, String tag) {
        String v = raw.get(tag);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
