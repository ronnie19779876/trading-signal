package org.jdkxx.trader.core.marketdata;

import java.time.Duration;

/**
 * 测试用的配置夹具。
 * {@link MarketDataProperties} 是位置参数的 record，每加一个配置项就要改遍所有测试的构造调用
 * （已经因此断过三次构建），集中到一处。
 *
 * <p>子记录能填就填真实默认值：留 null 的那些在被读到时才炸，而且炸在无关的测试里
 * （universe 留 null，2026-09-25 加成分股守护时 6 个测试一起 NPE）。
 */
public final class TestProperties {

    private TestProperties() {
    }

    public static MarketDataProperties defaults() {
        return new MarketDataProperties(
                new MarketDataProperties.Universe("", "", "", true, "0 30 6 * * SAT", 200, 5, 5, "test"),
                null,
                new MarketDataProperties.Refresh(90, 65, true, 1000, 5, 5),
                null,
                null,
                null,
                new MarketDataProperties.Fundamentals(true, 400, 12, Duration.ofDays(30), Duration.ofDays(180)),
                false,
                "", "", "", "",
                "America/New_York");
    }
}
