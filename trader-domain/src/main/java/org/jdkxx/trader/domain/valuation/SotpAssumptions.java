package org.jdkxx.trader.domain.valuation;

import java.time.LocalDate;
import java.util.List;

/**
 * 一套分部估值假设。
 *
 * <p>净现金与股数都分「当前」与「目标年」两套：当前值由系统自动带入（资产负债表与估值快照），
 * 这里存的是<b>目标年预测值</b>，不默认等于当前值——Robotaxi 自持车队与 AI 算力是重资本开支，
 * 可能把现金耗尽；股权激励与回购也会改变股数。
 *
 * @param asOf          估值基准日（折现从这天算起）
 * @param targetYear    目标年，折现到目标年年末
 * @param discountRate  要求回报率，<b>小数</b>（10% 传 0.10）
 * @param targetShares  目标年总股数
 * @param targetNetCash 目标年净现金（可为负）
 * @param segments      业务线，至少一条
 */
public record SotpAssumptions(LocalDate asOf, int targetYear, double discountRate,
                              double targetShares, double targetNetCash, List<SotpSegment> segments) {

    public SotpAssumptions {
        if (asOf == null) {
            throw new IllegalArgumentException("缺估值基准日");
        }
        if (targetYear < asOf.getYear()) {
            throw new IllegalArgumentException("目标年 " + targetYear + " 早于基准日所在年 " + asOf.getYear());
        }
        if (discountRate <= -1.0 || discountRate > 1.0) {
            throw new IllegalArgumentException("要求回报率要传小数（10% 传 0.10），收到 " + discountRate);
        }
        if (targetShares <= 0) {
            throw new IllegalArgumentException("目标年股数必须为正");
        }
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("至少要有一条业务线");
        }
        segments = List.copyOf(segments);
    }

    /** 折现期：基准日到目标年年末的年数。 */
    public double horizonYears() {
        LocalDate end = LocalDate.of(targetYear, 12, 31);
        return java.time.temporal.ChronoUnit.DAYS.between(asOf, end) / 365.25d;
    }

    /**
     * 折现系数。<b>不可关闭</b>：业务价值 × 本益比得到的是目标年的价值，不折回今天就没法和现价比
     * （10%、4.3 年的系数是 0.664，差了三分之一）。
     */
    public double discountFactor() {
        return 1.0d / Math.pow(1.0d + discountRate, horizonYears());
    }
}
