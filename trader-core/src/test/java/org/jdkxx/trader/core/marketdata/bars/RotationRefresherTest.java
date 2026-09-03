package org.jdkxx.trader.core.marketdata.bars;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RotationRefresherTest {

    @Test
    void 分批() {
        assertThat(RotationRefresher.batches(List.of(1, 2, 3, 4, 5), 2)).containsExactly(List.of(1, 2), List.of(3, 4), List.of(5));
        assertThat(RotationRefresher.batches(List.of(), 2)).isEmpty();
    }
}
