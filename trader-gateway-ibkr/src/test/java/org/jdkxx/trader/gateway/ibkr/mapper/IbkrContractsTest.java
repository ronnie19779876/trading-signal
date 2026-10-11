package org.jdkxx.trader.gateway.ibkr.mapper;

import com.ib.client.Contract;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IbkrContractsTest {

    @Test
    void 美股映射为SMART路由的STK合约() {
        Contract c = IbkrContracts.usStock("aapl");

        assertThat(c.symbol()).isEqualTo("AAPL");
        assertThat(c.getSecType()).isEqualTo("STK");   // secType() 返回枚举，getSecType() 才是字符串
        assertThat(c.exchange()).isEqualTo("SMART");
        assertThat(c.currency()).isEqualTo("USD");
        assertThat(c.primaryExch()).isEqualTo("NASDAQ");
    }

    /** 类别股盈透写空格：不换的话 contractDetails 查无此标的（2026-10-10 开发实例实测 BRK.B 空、BRK B 有）。 */
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

    /** SMART 的历史只从最近一次转板开始（实测 PEP），取历史要按主交易所；查不到主交易所才退回 SMART。 */
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
