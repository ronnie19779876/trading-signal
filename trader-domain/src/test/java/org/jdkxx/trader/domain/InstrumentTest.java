package org.jdkxx.trader.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstrumentTest {

    @Test
    void 代码规范化为大写并去掉空白() {
        assertThat(Instrument.us(" aapl ").symbol()).isEqualTo("AAPL");
        assertThat(Instrument.us("aapl")).isEqualTo(Instrument.us("AAPL"));
    }

    @Test
    void 空代码被拒绝() {
        assertThatThrownBy(() -> Instrument.us(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
