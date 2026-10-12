package org.jdkxx.trader.core.selection;

import org.jdkxx.trader.domain.selection.PriceSeam;
import org.jdkxx.trader.domain.selection.ValuationRange;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.LocalDate;

/**
 * 量化选股（{@code trader.selection.*}，ARCHITECTURE §25）。评分参数跟着模型版本写死在代码里，这里只有开关、时点与输入回补的取数范围。
 * 三个 cron 必须同时写进 jar 内 application.yml（占位符解析不看这里的默认值，见 ScheduledPlaceholdersTest）。
 *
 * @param enabled          关掉后不装配定时任务（手工接口照常可用）
 * @param dailyCron        每日评分：排在估值快照 17:40、信号评估 18:10 之后
 * @param catchupCron      当天没有评分就补跑
 * @param weeklyCron       每周刷新公告日与证券类型
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
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0 30 18 * * MON-FRI") String dailyCron,
        @DefaultValue("0 30 22 * * MON-FRI") String catchupCron,
        @DefaultValue("0 0 9 * * SAT") String weeklyCron,
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
