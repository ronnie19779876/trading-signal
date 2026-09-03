package org.jdkxx.trader.gateway.ibkr.mapper;

import com.ib.client.ContractDetails;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentInfo;

import java.math.BigDecimal;
import java.time.ZoneId;

/**
 * TWS 合约明细 → 领域参考数据。
 */
public final class IbkrInstruments {

    private IbkrInstruments() {
    }

    public static InstrumentInfo toInfo(Instrument instrument, ContractDetails cd) {
        return new InstrumentInfo(
                instrument,
                Broker.IBKR,
                Integer.toString(cd.conid()),
                cd.longName(),
                cd.contract() == null ? null : cd.contract().primaryExch(),
                cd.contract() == null ? null : cd.contract().currency(),
                cd.minTick() > 0 ? BigDecimal.valueOf(cd.minTick()).stripTrailingZeros() : null,
                zone(cd.timeZoneId()),
                cd.tradingHours(),
                cd.liquidHours());
    }

    static ZoneId zone(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(id.trim(), ZoneId.SHORT_IDS);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
