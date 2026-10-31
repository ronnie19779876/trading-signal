package org.jdkxx.trader.core.account;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.gateway.AccountGateway;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches IBKR positions and maps them to this system's instruments. Shared by the account snapshot
 * and the holding sync. Blocking waits are bounded by {@link #TIMEOUT_SECONDS}; callers run on job
 * or request threads, never on a gateway thread.
 */
final class AccountPositions {

  private static final Logger log = LoggerFactory.getLogger(AccountPositions.class);
  static final long TIMEOUT_SECONDS = 30;

  private final String configuredAccount;
  private final BrokerGateway broker;
  private final AccountGateway accounts;
  private final InstrumentRepository instruments;

  AccountPositions(
      String configuredAccount,
      BrokerGateway broker,
      AccountGateway accounts,
      InstrumentRepository instruments) {
    this.configuredAccount = configuredAccount;
    this.broker = broker;
    this.accounts = accounts;
    this.instruments = instruments;
  }

  /**
   * Chooses the account: trader.ibkr.account when configured (it must be in the managed list),
   * otherwise the only managed account if there is exactly one. Exception messages never contain
   * account numbers.
   */
  String chooseAccount() throws Exception {
    return choose(
        configuredAccount, await(broker.accounts()).stream().map(AccountRef::accountId).toList());
  }

  /** The account-selection rule itself (shared by the real-time account and the snapshot). */
  static String choose(String configuredAccount, List<String> ids) {
    if (configuredAccount != null && !configuredAccount.isBlank()) {
      String c = configuredAccount.trim();
      if (!ids.contains(c)) {
        throw new IllegalStateException("配置的 trader.ibkr.account 不在盈透受管账户列表里");
      }
      return c;
    }
    if (ids.size() == 1) {
      return ids.get(0);
    }
    throw new IllegalStateException(
        ids.isEmpty()
            ? "盈透没有返回受管账户"
            : "盈透有 " + ids.size() + " 个受管账户，请用 trader.ibkr.account 指定快照哪一个");
  }

  List<Position> positions(String accountId) throws Exception {
    return await(accounts.positions(accountId));
  }

  AccountSummary summary(String accountId) throws Exception {
    return await(accounts.accountSummary(accountId));
  }

  /**
   * IBKR position -&gt; this system's instrument: by conId first; an unbound US stock is looked up
   * by symbol (space -&gt; dot, IBKR reports class shares as {@code BRK B}) and, when found, its
   * conId is recorded. Returns null when the instrument is not in the database.
   */
  Long instrumentId(Position p) {
    Long conId = parseLong(p.brokerRef());
    if (conId != null) {
      Optional<Long> bound = instruments.findIdByIbkrConId(conId);
      if (bound.isPresent()) {
        return bound.get();
      }
    }
    if (!stockUsd(p) || p.symbol() == null) {
      return null;
    }
    Optional<InstrumentRow> row = instruments.find(Instrument.us(normalize(p.symbol())));
    if (row.isEmpty()) {
      return null;
    }
    if (conId != null) {
      try {
        if (!instruments.bindIbkrConId(row.get().id(), conId)) {
          log.warn("{} 在库里已绑定了别的盈透 conId，本次按代码匹配，未改绑", row.get().symbol());
        }
      } catch (RuntimeException e) {
        log.warn("{} 记录盈透 conId 失败：{}", row.get().symbol(), e.toString());
      }
    }
    return row.get().id();
  }

  static String normalize(String symbol) {
    return symbol == null ? null : symbol.trim().replace(' ', '.');
  }

  static boolean stockUsd(Position p) {
    return "STK".equals(p.securityType()) && "USD".equals(p.currency());
  }

  private static Long parseLong(String s) {
    try {
      return s == null ? null : Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Waits for a broker future; an ExecutionException is unwrapped to the original exception so the
   * upper layer (ApiExceptionHandler) can map it by type.
   */
  static <T> T await(CompletableFuture<T> f) throws Exception {
    try {
      return f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      throw e.getCause() instanceof Exception c ? c : e;
    }
  }
}
