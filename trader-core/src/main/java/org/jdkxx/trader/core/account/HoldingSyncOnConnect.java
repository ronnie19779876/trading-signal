package org.jdkxx.trader.core.account;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Syncs holdings once after IBKR connects (so the pool's HOLDING is aligned right after the Monday
 * re-login). Only wired on instances with scheduling enabled.
 *
 * <p>The callback runs on the gateway's scheduler thread and must not block, so the work is
 * deferred to this class's own thread. The 60-second delay gives Futu time to connect, because
 * positions not yet in the database are resolved via Futu before they are created. A failure is
 * only logged; the next snapshot or reconnect tries again.
 */
public class HoldingSyncOnConnect implements GatewayListener, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(HoldingSyncOnConnect.class);
  static final Duration DELAY = Duration.ofSeconds(60);

  private final HoldingSyncService sync;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "holding-sync");
            t.setDaemon(true);
            return t;
          });

  public HoldingSyncOnConnect(HoldingSyncService sync) {
    this.sync = sync;
  }

  @Override
  public void onConnected(Broker broker, boolean reconnected) {
    if (broker != Broker.IBKR) {
      return;
    }
    try {
      executor.schedule(this::syncQuietly, DELAY.toSeconds(), TimeUnit.SECONDS);
    } catch (RuntimeException e) {
      log.warn("排持仓同步失败：{}", e.toString());
    }
  }

  void syncQuietly() {
    try {
      HoldingSyncService.Result r = sync.syncNow(true, "SCHEDULE");
      log.info("盈透连上后持仓同步：{}", r.summary());
    } catch (Exception e) {
      log.warn("盈透连上后持仓同步失败（下次快照或重连时再试）：{}", e.toString());
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
