package org.jdkxx.trader.core.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.junit.jupiter.api.Test;

class SelectionTargetsTest {

  private final UniverseScope scope = mock(UniverseScope.class);
  private final SelectionTargets targets = new SelectionTargets(scope);

  private static InstrumentRow row(long id, String symbol, SecurityType type) {
    return new InstrumentRow(
        id, Market.US, symbol, symbol, null, type, 1, null, false, null, null, "RESOLVED");
  }

  @Test
  void 成分股与池合并去重_只留普通股_按代码排序() {
    when(scope.universe())
        .thenReturn(List.of(row(2, "MSFT", SecurityType.STOCK), row(3, "O", SecurityType.ETF)));
    when(scope.poolAndHoldings())
        .thenReturn(
            List.of(
                row(2, "MSFT", SecurityType.STOCK),
                row(1, "AAPL", SecurityType.STOCK),
                row(4, "SPY", SecurityType.ETF)));

    assertThat(targets.all()).extracting(InstrumentRow::symbol).containsExactly("AAPL", "MSFT");
  }

  @Test
  void 指定的代码不在范围内直接报错() {
    when(scope.universe())
        .thenReturn(List.of(row(2, "MSFT", SecurityType.STOCK), row(3, "O", SecurityType.ETF)));
    when(scope.poolAndHoldings()).thenReturn(List.of());

    assertThat(targets.only(List.of("msft")))
        .extracting(InstrumentRow::symbol)
        .containsExactly("MSFT");
    assertThatThrownBy(() -> targets.only(List.of("MSFT", "O", "ZZZZ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("O,ZZZZ");
  }
}
