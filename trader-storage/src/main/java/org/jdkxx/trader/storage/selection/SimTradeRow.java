package org.jdkxx.trader.storage.selection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 模拟交易一笔（{@code selection_sim_trade}），价格是评分日口径。{@code id} 新建前为 0，{@code symbol} 只在查询时由 instrument 带出。
 * 字段含义见 V19 迁移与 ARCHITECTURE §26。
 */
public record SimTradeRow(
        long id, String rulesVersion, long instrumentId, String symbol,
        String modelVersion, LocalDate scoreDate, int scoreRank, String rating, BigDecimal composite, Instant requestedAt,
        BigDecimal setupClose, BigDecimal setupAtr, BigDecimal formulaStop, BigDecimal stop, String stopLeg, BigDecimal resistance,
        LocalDate plannedEntryDate,
        String status, LocalDate entryDate, BigDecimal entryPrice, Integer shares, BigDecimal plusOneR, BigDecimal target,
        String targetSource, boolean touchedPlusOneR, LocalDate targetHitDate, BigDecimal activeStop, boolean chandelierActive,
        Instant closeRequestedAt, LocalDate plannedExitDate, String closeKind, String closeNote,
        LocalDate exitDate, BigDecimal exitPrice, String exitReason, BigDecimal pnl, BigDecimal returnPct, BigDecimal rMultiple,
        BigDecimal mfeR, BigDecimal maeR, Integer barsHeld, BigDecimal lastClose, LocalDate lastCloseDate, BigDecimal unrealizedPnl,
        BigDecimal spyReturn, BigDecimal excessReturn,
        String shadowStatus, LocalDate shadowExitDate, BigDecimal shadowExitPrice, String shadowExitReason, BigDecimal shadowPnl,
        BigDecimal shadowReturn, BigDecimal shadowLastClose,
        String cancelReason, LocalDate updatedThrough, Instant updatedAt) {

    public boolean active() {
        return "PENDING_ENTRY".equals(status) || "OPEN".equals(status);
    }

    /** 新加入、尚未入场的一笔。 */
    public static SimTradeRow pending(String rulesVersion, long instrumentId, String modelVersion, LocalDate scoreDate, int scoreRank,
                                      String rating, BigDecimal composite, Instant requestedAt, BigDecimal setupClose, BigDecimal setupAtr,
                                      BigDecimal formulaStop, BigDecimal stop, String stopLeg, BigDecimal resistance,
                                      LocalDate plannedEntryDate) {
        return new SimTradeRow(0, rulesVersion, instrumentId, null, modelVersion, scoreDate, scoreRank, rating, composite, requestedAt,
                setupClose, setupAtr, formulaStop, stop, stopLeg, resistance, plannedEntryDate,
                "PENDING_ENTRY", null, null, null, null, null, null, false, null, null, false,
                null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null);
    }
}
