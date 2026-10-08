package org.jdkxx.trader.storage.signal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 纸面账本一行（一条信号 × 一个出场变体）；价格为判定日口径。 */
public record SignalTrackRow(
        long signalId,
        String variant,
        String status,
        BigDecimal stop,
        BigDecimal plusOneR,
        LocalDate entryDate,
        BigDecimal entryPrice,
        boolean touchedPlusOneR,
        LocalDate exitDate,
        BigDecimal exitPrice,
        String exitReason,
        BigDecimal rMultiple,
        BigDecimal returnPct,
        BigDecimal mfeR,
        BigDecimal maeR,
        /** 当前价（判定日口径，与 entryPrice 可直接比）；只给 OPEN 填 */
        BigDecimal lastClose,
        /** 当前价那根 K 线的日期；<b>可能早于 updatedThrough</b>（停牌或缺 K 线），展示时必须一并给出 */
        LocalDate lastCloseDate,
        /** 浮动盈亏 ÷ R。R = 判定日收盘 − 本变体止损，所以两个变体通常不同（止损由区底腿决定时才相同） */
        BigDecimal unrealizedR,
        Integer barsHeld,
        LocalDate updatedThrough,
        Instant updatedAt) {
}
