package org.jdkxx.trader.gateway;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Position;

/**
 * Account port (phase 3): positions and the funds summary, all read-only. Only IBKR implements it
 * in this phase.
 *
 * <p>{@code accountId} is the broker's raw account number, obtained by the caller from {@link
 * BrokerGateway#accounts()}; neither implementations nor callers may put it into logs, exception
 * messages or API responses. Positions are a one-shot query. For IBKR the funds summary is
 * <em>not</em> a one-shot query since 3.0.9: it reads the latest batch of the standing
 * account-summary subscription (see {@link LiveAccountGateway} for why), so its values can be up to
 * one push interval (about 3 minutes) old.
 */
public interface AccountGateway {

  Broker broker();

  /**
   * All current positions exactly as the broker reports them (may include zero-quantity entries for
   * positions closed today; the caller decides whether to keep them).
   */
  CompletableFuture<List<Position>> positions(String accountId);

  /**
   * Funds summary. Concurrent calls for the same account are coalesced into a single broker
   * request.
   */
  CompletableFuture<AccountSummary> accountSummary(String accountId);
}
