package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 券商账户的资金汇总，券商原样口径。字段缺失为 null——券商对不同账户类型返回的标签不同。
 *
 * <p>恒等式：现金 + 股票市值 + 应计股息 + <b>应计利息</b> = 净值（盈透实测精确到分），第 3 期对账用它。
 * 应计利息（盈透 {@code $LEDGER-AccruedCash}）是 3.1.3 补上的：此前现金余额小、它一直是 0，
 * 三项相加正好成立；2026-09-28 现金涨到两万多之后它变成 1.49，等式差的就是这一项
 * （当时误判成对账 WARN、作业 PARTIAL，其实数据没错）。
 * raw 保留券商原始标签与值，<b>已剔除账户号</b>（盈透 {@code $LEDGER-AccountOrGroup} 的值就是明文账户号）。
 *
 * <p>accountId 是券商原始账户号，只在服务端内存里流转，{@link #toString()} 不带它。
 */
public record AccountSummary(
        Broker broker,
        String accountId,
        Instant receivedAt,
        String currency,
        BigDecimal netLiquidation,
        BigDecimal totalCash,
        BigDecimal stockMarketValue,
        BigDecimal grossPositionValue,
        BigDecimal availableFunds,
        BigDecimal buyingPower,
        BigDecimal excessLiquidity,
        BigDecimal unrealizedPnl,
        BigDecimal realizedPnl,
        BigDecimal accruedDividend,
        BigDecimal accruedInterest,
        Map<String, String> raw) {

    public AccountSummary {
        Objects.requireNonNull(broker, "broker");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
        raw = raw == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    /** 防止账户号被无意打进日志。 */
    @Override
    public String toString() {
        return "AccountSummary[" + broker + " **** " + currency + " @" + receivedAt + " tags=" + raw.size() + "]";
    }
}
