package org.jdkxx.trader.core.selection;

import org.jdkxx.trader.domain.selection.PriceSeam;
import org.jdkxx.trader.domain.selection.ValuationRange;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.LocalDate;

/**
 * 量化选股（{@code trader.selection.*}，ARCHITECTURE §25）。评分参数跟着模型版本写死在代码里，这里只有输入回补的取数范围。
 *
 * @param priceFrom        盈透日线从哪天起补（12 个月动能从 2018 回测要 2017 起，留一年余量）
 * @param seamMonths       盈透日线取到富途日 K 起点之后几个月，保证有重叠段可核对
 * @param financialPeriods 季报取多少期（富途单次上限 50，实测约回到 2014）
 * @param valuationRange   估值历史回看区间
 * @param seamMinCompared  接缝核对至少比较多少对
 * @param seamTolerance    接缝单日收益差容忍度（比例）
 * @param seamMinShare     接缝达标占比下限
 */
@ConfigurationProperties(prefix = "trader.selection")
public record SelectionProperties(
        @DefaultValue("2016-01-01") LocalDate priceFrom,
        @DefaultValue("4") int seamMonths,
        @DefaultValue("50") int financialPeriods,
        @DefaultValue("YEAR10") ValuationRange valuationRange,
        @DefaultValue("60") int seamMinCompared,
        @DefaultValue("0.001") double seamTolerance,
        @DefaultValue("0.95") double seamMinShare) {

    public PriceSeam.Thresholds seamThresholds() {
        return new PriceSeam.Thresholds(seamMinCompared, seamTolerance, seamMinShare);
    }
}
