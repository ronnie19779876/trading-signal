package org.jdkxx.trader.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Reference to a broker account. {@code accountId} is the broker-side account number (IBKR
 * "U..."/"DU...", Futu numeric accID). It is sensitive: it only travels in server-side memory and
 * is always masked externally.
 */
public record AccountRef(Broker broker, String accountId, AccountKind kind, Set<Market> markets) {

  public AccountRef {
    Objects.requireNonNull(broker, "broker");
    Objects.requireNonNull(kind, "kind");
    if (accountId == null || accountId.isBlank()) {
      throw new IllegalArgumentException("accountId 不能为空");
    }
    markets =
        markets == null || markets.isEmpty()
            ? Collections.emptySet()
            : Collections.unmodifiableSet(EnumSet.copyOf(markets));
  }

  /** Keeps the account number from being logged by accident. */
  @Override
  public String toString() {
    return "AccountRef[" + broker + " " + kind + " ****" + " " + markets + "]";
  }
}
