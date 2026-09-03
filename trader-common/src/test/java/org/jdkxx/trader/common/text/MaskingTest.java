package org.jdkxx.trader.common.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingTest {

    @Test
    void 保留前缀其余打星且不泄露长度() {
        assertThat(Masking.mask("ABCDEFGH", 2)).isEqualTo("AB*****");
        assertThat(Masking.mask("AB", 2)).isEqualTo("*****");
        assertThat(Masking.mask("   ", 2)).isEmpty();
        assertThat(Masking.mask(null, 2)).isEmpty();
    }
}
