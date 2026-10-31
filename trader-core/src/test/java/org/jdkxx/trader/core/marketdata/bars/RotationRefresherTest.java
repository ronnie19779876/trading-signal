package org.jdkxx.trader.core.marketdata.bars;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RotationRefresherTest {

  @Test
  void 分批() {
    assertThat(RotationRefresher.batches(List.of(1, 2, 3, 4, 5), 2))
        .containsExactly(List.of(1, 2), List.of(3, 4), List.of(5));
    assertThat(RotationRefresher.batches(List.of(), 2)).isEmpty();
  }
}
