package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FutuBarsTest {

    @Test
    void 富途日K映射为不复权日K() {
        QotCommon.KLine k = QotCommon.KLine.newBuilder().setTime("2026-09-02 00:00:00").setIsBlank(false)
                .setOpenPrice(326.865).setHighPrice(328.4).setLowPrice(323.53).setClosePrice(324.96).setLastClosePrice(325.13)
                .setVolume(33776370L).setTurnover(1.098668091E10).setTurnoverRate(0.00232).setPe(43.56).setChangeRate(-0.05228677759665855)
                .build();

        List<DailyBar> bars = FutuBars.toDailyBars(Instrument.us("AAPL"), List.of(k));

        DailyBar b = bars.get(0);
        assertThat(b.tradeDate()).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(b.close()).isEqualByComparingTo(new BigDecimal("324.96"));
        assertThat(b.open().toPlainString()).isEqualTo("326.865");
        assertThat(b.volume()).isEqualTo(33776370L);
        assertThat(b.turnover()).isEqualByComparingTo(new BigDecimal("10986680910"));
        assertThat(b.turnoverRate().toPlainString()).isEqualTo("0.00232");
        assertThat(b.changeRate().toPlainString()).isEqualTo("-0.052287");
        assertThat(b.blank()).isFalse();
    }
}
