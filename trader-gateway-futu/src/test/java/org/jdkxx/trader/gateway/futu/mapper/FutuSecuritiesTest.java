package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FutuSecuritiesTest {

    @Test
    void 美股与港股映射到对应的QotMarket() {
        QotCommon.Security us = FutuSecurities.of(Instrument.us("aapl"));
        assertThat(us.getMarket()).isEqualTo(QotCommon.QotMarket.QotMarket_US_Security_VALUE);
        assertThat(us.getCode()).isEqualTo("AAPL");

        QotCommon.Security hk = FutuSecurities.of(new Instrument(Market.HK, "00700"));
        assertThat(hk.getMarket()).isEqualTo(QotCommon.QotMarket.QotMarket_HK_Security_VALUE);
    }

    @Test
    void 重定位后的protobuf运行时可以序列化往返() throws Exception {
        QotCommon.Security sec = FutuSecurities.of(Instrument.us("MSFT"));
        QotCommon.Security back = QotCommon.Security.parseFrom(sec.toByteArray());
        assertThat(back).isEqualTo(sec);
        // 证明用的是重定位后的运行时，而不是盈透那边的 protobuf 4.x
        assertThat(sec.getClass().getSuperclass().getName()).startsWith("org.jdkxx.trader.shaded.futu.protobuf.");
    }
}
