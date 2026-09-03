package org.jdkxx.trader.core.marketdata.universe;

import org.jdkxx.trader.domain.IndexCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvUniverseTest {

    @Test
    void 解析带表头注释与可选列的CSV() {
        List<ConstituentEntry> out = CsvUniverse.parse("""
                index_code,symbol,name
                # 注释
                SP500, aapl ,Apple
                NDX100,MSFT
                """);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).index()).isEqualTo(IndexCode.SP500);
        assertThat(out.get(0).symbol()).isEqualTo("AAPL");
        assertThat(out.get(0).name()).isEqualTo("Apple");
        assertThat(out.get(1).name()).isNull();
    }

    @Test
    void 非法行报错() {
        assertThatThrownBy(() -> CsvUniverse.parse("DOW30,AAPL")).hasMessageContaining("未知指数");
        assertThatThrownBy(() -> CsvUniverse.parse("AAPL")).hasMessageContaining("至少");
    }
}
