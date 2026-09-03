package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;

/**
 * 领域标的 → 富途行情证券标识。Java SDK 里 code 不带市场前缀（"AAPL"、"00700"），市场用枚举表达。
 */
public final class FutuSecurities {

    private FutuSecurities() {
    }

    public static QotCommon.Security of(Instrument instrument) {
        return QotCommon.Security.newBuilder()
                .setMarket(market(instrument.market()))
                .setCode(instrument.symbol())
                .build();
    }

    public static int market(Market market) {
        return switch (market) {
            case US -> QotCommon.QotMarket.QotMarket_US_Security_VALUE;
            case HK -> QotCommon.QotMarket.QotMarket_HK_Security_VALUE;
        };
    }
}
