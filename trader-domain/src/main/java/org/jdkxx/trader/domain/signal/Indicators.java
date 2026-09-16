package org.jdkxx.trader.domain.signal;

import java.util.Arrays;

/**
 * 技术指标（纯函数）。样本不足的位置一律输出 NaN，调用方把 NaN 判为 UNAVAILABLE，不当 0 用。
 *
 * <p>口径：
 * <ul>
 *   <li>SMA[t] = (X[t−n+1] + … + X[t]) / n；</li>
 *   <li>TR[0] = H−L；TR[i] = max(H−L, |H−C[i−1]|, |L−C[i−1]|)；</li>
 *   <li>ATR 用 Wilder 平滑：ATR[n−1] = 前 n 根 TR 的算术平均，之后 ATR[i] = (ATR[i−1]×(n−1) + TR[i]) / n。
 *       行情终端显示的 ATR 常是 SMA(TR, n)，两者是不同公式、不随时间收敛；
 *       Wilder 递推与路径有关，所以判定的取数窗口必须固定；</li>
 *   <li>EMA[0] = X[0]；EMA[i] = X[i]×k + EMA[i−1]×(1−k)，k = 2/(n+1)；</li>
 *   <li>MACD 柱 = (DIF − DEA) × 2，DIF = EMA12 − EMA26，DEA = EMA(DIF, 9)；幅度为国际口径两倍，符号一致；</li>
 *   <li>RVOL[t] = V[t] ÷ mean(V[t−n … t−1])，<b>分母不含当日</b>。</li>
 * </ul>
 */
public final class Indicators {

    private Indicators() {
    }

    public static double[] sma(double[] x, int n) {
        double[] out = nan(x.length);
        double sum = 0;
        for (int i = 0; i < x.length; i++) {
            sum += x[i];
            if (i >= n) {
                sum -= x[i - n];
            }
            if (i >= n - 1) {
                out[i] = sum / n;
            }
        }
        return out;
    }

    public static double[] trueRange(double[] high, double[] low, double[] close) {
        double[] tr = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            double hl = high[i] - low[i];
            tr[i] = i == 0 ? hl : Math.max(hl, Math.max(Math.abs(high[i] - close[i - 1]), Math.abs(low[i] - close[i - 1])));
        }
        return tr;
    }

    public static double[] wilderAtr(double[] high, double[] low, double[] close, int n) {
        double[] tr = trueRange(high, low, close);
        double[] out = nan(tr.length);
        if (tr.length < n) {
            return out;
        }
        double seed = 0;
        for (int i = 0; i < n; i++) {
            seed += tr[i];
        }
        out[n - 1] = seed / n;
        for (int i = n; i < tr.length; i++) {
            out[i] = (out[i - 1] * (n - 1) + tr[i]) / n;
        }
        return out;
    }

    public static double[] ema(double[] x, int n) {
        double[] out = nan(x.length);
        if (x.length == 0) {
            return out;
        }
        double k = 2.0 / (n + 1);
        out[0] = x[0];
        for (int i = 1; i < x.length; i++) {
            out[i] = x[i] * k + out[i - 1] * (1 - k);
        }
        return out;
    }

    public static double[] macdHistogram(double[] close, int fast, int slow, int signal) {
        double[] f = ema(close, fast);
        double[] s = ema(close, slow);
        double[] dif = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            dif[i] = f[i] - s[i];
        }
        double[] dea = ema(dif, signal);
        double[] hist = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            hist[i] = (dif[i] - dea[i]) * 2;
        }
        return hist;
    }

    /** 前 n 根（不含 t）的平均成交量；样本不足或均量为 0 时 NaN。 */
    public static double priorAverageVolume(double[] volume, int t, int n) {
        if (t < n) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = t - n; i < t; i++) {
            sum += volume[i];
        }
        double avg = sum / n;
        return avg > 0 ? avg : Double.NaN;
    }

    public static double relativeVolume(double[] volume, int t, int n) {
        double avg = priorAverageVolume(volume, t, n);
        return Double.isNaN(avg) ? Double.NaN : volume[t] / avg;
    }

    private static double[] nan(int length) {
        double[] out = new double[length];
        Arrays.fill(out, Double.NaN);
        return out;
    }
}
