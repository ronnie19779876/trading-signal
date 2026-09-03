package org.jdkxx.trader.common.env;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppEnvironmentTest {

    @Test
    void 大小写与首尾空白都能解析() {
        assertThat(AppEnvironment.parse(" dev ")).isEqualTo(AppEnvironment.DEV);
        assertThat(AppEnvironment.parse("PROD")).isEqualTo(AppEnvironment.PROD);
    }

    @Test
    void 未声明时给出修复提示() {
        assertThatThrownBy(() -> AppEnvironment.parse(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trader.environment");
    }

    @Test
    void 非法值被拒绝() {
        assertThatThrownBy(() -> AppEnvironment.parse("staging"))
                .hasMessageContaining("只能是 DEV 或 PROD");
    }
}
