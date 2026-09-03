package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.RehabFactor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FutuRehabsTest {

    @Test
    void 复权因子映射保留精度() {
        QotCommon.Rehab r = QotCommon.Rehab.newBuilder().setTime("2026-08-10").setCompanyActFlag(64)
                .setFwdFactorA(0.99913).setFwdFactorB(0).setBwdFactorA(1).setBwdFactorB(0.27).setDividend(0.27).build();

        List<RehabFactor> f = FutuRehabs.toFactors(Instrument.us("AAPL"), List.of(r));

        assertThat(f.get(0).exDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(f.get(0).fwdA()).isEqualByComparingTo(new BigDecimal("0.99913"));
        assertThat(f.get(0).bwdB()).isEqualByComparingTo(new BigDecimal("0.27"));
        assertThat(f.get(0).dividend()).isEqualByComparingTo(new BigDecimal("0.27"));
        assertThat(f.get(0).splitBase()).isZero();
    }
}
