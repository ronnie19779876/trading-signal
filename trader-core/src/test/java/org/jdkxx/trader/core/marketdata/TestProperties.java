package org.jdkxx.trader.core.marketdata;

import java.time.Duration;

/**
 * 测试用的配置夹具。
 * {@link MarketDataProperties} 是位置参数的 record，每加一个配置项就要改遍所有测试的构造调用
 * （已经因此断过三次构建），集中到一处。
 */
public final class TestProperties {

    private TestProperties() {
    }

    public static MarketDataProperties defaults() {
        return new MarketDataProperties(
                null,
                null,
                new MarketDataProperties.Refresh(90, 65, true, 1000, 5),
                null,
                null,
                null,
                new MarketDataProperties.Fundamentals(true, 400, 12, Duration.ofDays(30), Duration.ofDays(180)),
                false,
                "", "", "", "",
                "America/New_York");
    }
}
