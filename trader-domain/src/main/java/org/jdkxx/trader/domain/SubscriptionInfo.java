package org.jdkxx.trader.domain;

import java.time.Instant;
import java.util.Map;

/** 券商侧的订阅额度：全部连接合计。byType 为「类型 → 订阅只数」（本连接）。 */
public record SubscriptionInfo(int usedQuota, int remainQuota, Map<String, Integer> byType, Instant checkedAt) {

    public int totalQuota() {
        return usedQuota + remainQuota;
    }
}
