package org.jdkxx.trader.domain.signal;

import java.util.Arrays;

/**
 * Technical indicators (pure functions). Positions without enough samples are always NaN; callers
 * treat NaN as UNAVAILABLE and never as 0.
 *
 * <p>Definitions:
 *
 * <ul>
 *   <li>SMA[t] = (X[t−n+1] + … + X[t]) / n;
 *   <li>TR[0] = H−L; TR[i] = max(H−L, |H−C[i−1]|, |L−C[i−1]|);
 *   <li>ATR uses Wilder smoothing: ATR[n−1] = arithmetic mean of the first n TRs, then ATR[i] =
 *       (ATR[i−1]×(n−1) + TR[i]) / n. Charting terminals often show SMA(TR, n) as ATR; the two are
 *       different formulas and do not converge over time. The Wilder recursion depends on the path,
 *       so the data window used for evaluation must be fixed;
 *   <li>EMA[0] = X[0]; EMA[i] = X[i]×k + EMA[i−1]×(1−k), k = 2/(n+1);
 *   <li>MACD histogram = (DIF − DEA) × 2, DIF = EMA12 − EMA26, DEA = EMA(DIF, 9); the magnitude is
 *       twice the international convention, the sign is the same;
 *   <li>RVOL[t] = V[t] ÷ mean(V[t−n … t−1]); <b>the denominator excludes the current day</b>.
 * </ul>
 */
public final class Indicators {

  private Indicators() {}

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
      tr[i] =
          i == 0
              ? hl
              : Math.max(
                  hl, Math.max(Math.abs(high[i] - close[i - 1]), Math.abs(low[i] - close[i - 1])));
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

  /**
   * Average volume of the n bars before t (excluding t); NaN when there are too few samples or the
   * average is 0.
   */
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
