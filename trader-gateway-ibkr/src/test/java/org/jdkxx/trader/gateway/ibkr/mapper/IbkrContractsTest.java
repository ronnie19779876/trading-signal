package org.jdkxx.trader.gateway.ibkr.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ib.client.Contract;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.junit.jupiter.api.Test;

class IbkrContractsTest {

  @Test
  void 美股映射为SMART路由的STK合约() {
    Contract c = IbkrContracts.usStock("aapl");

    assertThat(c.symbol()).isEqualTo("AAPL");
    assertThat(c.getSecType())
        .isEqualTo("STK"); // secType() returns an enum; getSecType() is the string
    assertThat(c.exchange()).isEqualTo("SMART");
    assertThat(c.currency()).isEqualTo("USD");
    assertThat(c.primaryExch()).isEqualTo("NASDAQ");
  }

  /**
   * IBKR writes class shares with a space: without the swap contractDetails finds nothing (measured
   * on the dev instance 2026-10-10: BRK.B empty, BRK B found).
   */
  @Test
  void 类别股的点换成空格() {
    assertThat(IbkrContracts.usStock("BRK.B").symbol()).isEqualTo("BRK B");
  }

  @Test
  void 按conId定位只给conId与SMART() {
    Contract c = IbkrContracts.byConId(265598L);

    assertThat(c.conid()).isEqualTo(265598);
    assertThat(c.exchange()).isEqualTo("SMART");
    assertThat(c.symbol()).isNull();
  }

  /**
   * SMART history only starts at the most recent exchange transfer (measured on PEP), so history
   * must be requested by primary exchange; fall back to SMART only when the primary exchange is
   * unknown.
   */
  @Test
  void 取历史按主交易所_缺省退回SMART() {
    assertThat(IbkrContracts.historical(11017L, "NASDAQ").exchange()).isEqualTo("NASDAQ");
    assertThat(IbkrContracts.historical(11017L, "NASDAQ").conid()).isEqualTo(11017);
    assertThat(IbkrContracts.historical(11017L, null).exchange()).isEqualTo("SMART");
    assertThat(IbkrContracts.historical(11017L, " ").exchange()).isEqualTo("SMART");
  }

  @Test
  void 非美股暂不支持() {
    assertThatThrownBy(() -> IbkrContracts.stock(new Instrument(Market.HK, "00700"), null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
