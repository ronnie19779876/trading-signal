package org.jdkxx.trader.domain.signal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class IndicatorsTest {

    @Test
    void SMA样本不足输出NaN() {
        double[] out = Indicators.sma(new double[]{1, 2, 3, 4, 5}, 3);

        assertThat(out[0]).isNaN();
        assertThat(out[1]).isNaN();
        assertThat(out[2]).isEqualTo(2.0);
        assertThat(out[4]).isEqualTo(4.0);
    }

    @Test
    void ATR用Wilder平滑_种子是前n根TR的算术平均() {
        double[] high = {10, 12, 13, 12};
        double[] low = {9, 10, 11, 8};
        double[] close = {9.5, 11, 12, 9};
        // TR: 1（首根 H−L）、max(2, 2.5, 0.5)=2.5、max(2, 2, 0)=2、max(4, 0, 4)=4
        double[] atr = Indicators.wilderAtr(high, low, close, 3);

        assertThat(atr[1]).isNaN();
        assertThat(atr[2]).isCloseTo((1 + 2.5 + 2) / 3, within(1e-12));
        assertThat(atr[3]).isCloseTo((atr[2] * 2 + 4) / 3, within(1e-12));
        // 不是 SMA(TR, 3) = (2.5+2+4)/3
        assertThat(atr[3]).isNotCloseTo((2.5 + 2 + 4) / 3, within(1e-6));
    }

    @Test
    void EMA种子取首值() {
        double[] ema = Indicators.ema(new double[]{10, 20}, 3);

        assertThat(ema[0]).isEqualTo(10.0);
        assertThat(ema[1]).isEqualTo(20 * 0.5 + 10 * 0.5);
    }

    @Test
    void 常数序列的MACD柱为0() {
        double[] hist = Indicators.macdHistogram(new double[]{5, 5, 5, 5, 5}, 12, 26, 9);

        assertThat(hist).containsOnly(0.0);
    }

    @Test
    void RVOL分母不含当日() {
        double[] volume = new double[21];
        java.util.Arrays.fill(volume, 100);
        volume[20] = 1000;

        assertThat(Indicators.relativeVolume(volume, 20, 20)).isEqualTo(10.0);
        assertThat(Indicators.relativeVolume(volume, 19, 20)).isNaN();
    }

    @Test
    void 前期均量为0时RVOL不可判定() {
        assertThat(Indicators.relativeVolume(new double[]{0, 0, 5}, 2, 2)).isNaN();
    }
}
