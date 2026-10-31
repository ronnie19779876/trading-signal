package org.jdkxx.trader.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Broker-side subscription quota, summed over all connections. {@code byType} is "subscription type
 * -&gt; number of subscribed instruments" (this connection only).
 */
public record SubscriptionInfo(
    int usedQuota, int remainQuota, Map<String, Integer> byType, Instant checkedAt) {

  public int totalQuota() {
    return usedQuota + remainQuota;
  }
}
