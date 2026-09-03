package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import org.jdkxx.trader.domain.InstrumentStatic;
import org.jdkxx.trader.domain.SecurityType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FutuStaticsTest {

    private static QotCommon.SecurityStaticInfo info(String code, String name, int secType, String listTime) {
        return QotCommon.SecurityStaticInfo.newBuilder().setBasic(QotCommon.SecurityStaticBasic.newBuilder()
                .setSecurity(QotCommon.Security.newBuilder().setMarket(11).setCode(code)).setId(1).setLotSize(1)
                .setSecType(secType).setName(name).setListTime(listTime).setDelisting(false).setExchType(5)).build();
    }

    @Test
    void 股票与ETF与未知上市日() {
        List<InstrumentStatic> out = FutuStatics.toStatics(List.of(
                info("AAPL", "苹果", 3, "1980-12-12"), info("SPY", "标普500ETF", 4, "1970-01-01")));

        assertThat(out.get(0).type()).isEqualTo(SecurityType.STOCK);
        assertThat(out.get(0).listDate()).isEqualTo(LocalDate.of(1980, 12, 12));
        assertThat(out.get(1).type()).isEqualTo(SecurityType.ETF);
        assertThat(out.get(1).listDate()).isNull();
        assertThat(out.get(1).instrument().symbol()).isEqualTo("SPY");
    }
}
