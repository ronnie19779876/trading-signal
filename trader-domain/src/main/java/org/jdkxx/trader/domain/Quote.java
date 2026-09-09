package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 实时报价（内存对象，不落库）。
 * price/change/changeRate 是按时段取的"有效价"：RTH 与 CLOSED 用常规时段 curPrice，
 * PRE / AFTER / OVERNIGHT 用对应子结构（券商在非常规时段冻结 curPrice，只更新子结构）。
 *
 * @param rthPrice  常规时段价（券商 curPrice，非常规时段冻结在上个收盘）
 * @param lastClose 券商给的"昨收"：<b>常规时段的前一交易日收盘</b>。非常规时段它不随时段推进，
 *                  仍是上一个常规时段的前收，别拿它当盘前盘后涨跌的基准
 * @param referenceClose 当前 change / changeRate 实际用的基准价：常规时段与收市为 lastClose，
 *                  盘前盘后与夜盘为 rthPrice（上一个常规时段收盘）。展示"参考价"用这个
 * @param quoteTime 券商报价时间（常规时段的 updateTime；非常规时段可能仍是收盘时刻）
 */
public record Quote(
        Instrument instrument,
        MarketSession session,
        BigDecimal price,
        BigDecimal change,
        BigDecimal changeRate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal rthPrice,
        BigDecimal lastClose,
        BigDecimal referenceClose,
        long volume,
        BigDecimal turnover,
        SessionQuote preMarket,
        SessionQuote afterMarket,
        SessionQuote overnight,
        Instant quoteTime,
        Instant receivedAt,
        boolean suspended) {

    public Quote {
        Objects.requireNonNull(instrument, "instrument");
        session = session == null ? MarketSession.CLOSED : session;
        receivedAt = receivedAt == null ? Instant.now() : receivedAt;
    }

    /** 非常规时段的一组数据。 */
    public record SessionQuote(BigDecimal price, BigDecimal change, BigDecimal changeRate, long volume) {
    }
}
