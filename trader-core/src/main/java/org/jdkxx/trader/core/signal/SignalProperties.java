package org.jdkxx.trader.core.signal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 入场信号（{@code trader.signal.*}）。判据参数不在这里——参数跟着判据版本写死在代码里（{@code SentinelThresholds}）。
 * 两个 cron 必须同时写进 jar 内 application.yml（占位符解析不看这里的默认值，见 ScheduledPlaceholdersTest）。
 *
 * @param enabled         关掉后不装配定时评估（手工接口照常可用）
 * @param evaluationCron  每日评估：排在增量 17:30、估值 17:40、账户快照（含持仓同步）18:00 之后
 * @param catchupCron     信号补偿检查：与 21:00 的行情补偿错开，等行情补跑完
 */
@ConfigurationProperties(prefix = "trader.signal")
public record SignalProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0 10 18 * * MON-FRI") String evaluationCron,
        @DefaultValue("0 0 22 * * MON-FRI") String catchupCron) {
}
