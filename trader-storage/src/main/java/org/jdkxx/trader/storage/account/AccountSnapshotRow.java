package org.jdkxx.trader.storage.account;

import com.fasterxml.jackson.annotation.JsonRawValue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 账户快照一行。accountKey 是带密钥的 HMAC，accountMask 是展示用脱敏形式；明文账户号不落库。
 * recon 是对账明细的 JSON 原文，接口里原样嵌入。
 */
public record AccountSnapshotRow(
        long id,
        String broker,
        String accountKey,
        String accountMask,
        LocalDate asOfDate,
        Instant takenAt,
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
        BigDecimal positionValue,
        int positions,
        String reconStatus,
        @JsonRawValue String recon,
        Long jobRunId) {
}
