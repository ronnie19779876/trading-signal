package org.jdkxx.trader.gateway.futu.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.futu.openapi.pb.QotCommon;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.RehabFactor;
import org.junit.jupiter.api.Test;

class FutuRehabsTest {

  @Test
  void 复权因子映射保留精度() {
    QotCommon.Rehab r =
        QotCommon.Rehab.newBuilder()
            .setTime("2026-08-10")
            .setCompanyActFlag(64)
            .setFwdFactorA(0.99913)
            .setFwdFactorB(0)
            .setBwdFactorA(1)
            .setBwdFactorB(0.27)
            .setDividend(0.27)
            .build();

    List<RehabFactor> f = FutuRehabs.toFactors(Instrument.us("AAPL"), List.of(r));

    assertThat(f.get(0).exDate()).isEqualTo(LocalDate.of(2026, 8, 10));
    assertThat(f.get(0).fwdA()).isEqualByComparingTo(new BigDecimal("0.99913"));
    assertThat(f.get(0).bwdB()).isEqualByComparingTo(new BigDecimal("0.27"));
    assertThat(f.get(0).dividend()).isEqualByComparingTo(new BigDecimal("0.27"));
    assertThat(f.get(0).splitBase()).isZero();
  }

  @Test
  void 复权因子不是有限数时报错并带标的与日期_分红置空() {
    QotCommon.Rehab nanFactor =
        QotCommon.Rehab.newBuilder()
            .setTime("2026-08-10")
            .setCompanyActFlag(64)
            .setFwdFactorA(Double.NaN)
            .setFwdFactorB(0)
            .setBwdFactorA(1)
            .setBwdFactorB(0)
            .build();
    assertThatThrownBy(() -> FutuRehabs.toFactors(Instrument.us("AAPL"), List.of(nanFactor)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AAPL")
        .hasMessageContaining("2026-08-10");

    QotCommon.Rehab nanDividend =
        nanFactor.toBuilder().setFwdFactorA(1).setDividend(Double.NaN).build();
    assertThat(FutuRehabs.toFactors(Instrument.us("AAPL"), List.of(nanDividend)).get(0).dividend())
        .isNull();
  }
}
