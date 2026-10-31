package org.jdkxx.trader.core.gateway;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.GatewayListener;
import org.jdkxx.trader.storage.gateway.GatewayEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Logs gateway connection events and stores them in the gateway_event table.
 *
 * <p>Thread discipline: listener callbacks arrive on the gateway's scheduler thread, which runs the
 * connection state machine and must never block on JDBC. The insert is therefore handed to this
 * class's own single-threaded executor; a single thread also keeps the rows in event order. A
 * failed insert is only logged.
 */
@Component
public class GatewayEventRecorder implements GatewayListener {

  private static final Logger log = LoggerFactory.getLogger(GatewayEventRecorder.class);

  private final ObjectProvider<GatewayEventRepository> repository;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "gateway-events");
            t.setDaemon(true);
            return t;
          });

  public GatewayEventRecorder(ObjectProvider<GatewayEventRepository> repository) {
    this.repository = repository;
  }

  @Override
  public void onConnected(Broker broker, boolean reconnected) {
    record(broker, reconnected ? "RECONNECTED" : "CONNECTED", reconnected ? "断线后重新连上" : "首次连上");
  }

  @Override
  public void onDisconnected(Broker broker, String reason) {
    record(broker, "DISCONNECTED", reason);
  }

  @Override
  public void onError(Broker broker, GatewayException error) {
    record(broker, "ERROR", error.getMessage());
  }

  private void record(Broker broker, String event, String detail) {
    Instant at = Instant.now();
    log.info("网关事件 {} {}：{}", broker, event, detail);
    GatewayEventRepository repo = repository.getIfAvailable();
    if (repo == null) {
      return;
    }
    executor.execute(
        () -> {
          try {
            repo.insert(broker, event, detail, at);
          } catch (RuntimeException e) {
            log.warn("写 gateway_event 失败：{}", e.toString());
          }
        });
  }

  /**
   * Waits for the already-submitted inserts to finish. On shutdown the gateways are disconnected
   * before the data source closes, and the final DISCONNECTED row must not be lost. Submitting an
   * empty task and waiting on it works because the executor is single-threaded and FIFO.
   */
  public void flush(java.time.Duration timeout) {
    try {
      executor.submit(() -> {}).get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      log.warn("等待网关事件落库超时或失败：{}", e.toString());
    }
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
  }
}
