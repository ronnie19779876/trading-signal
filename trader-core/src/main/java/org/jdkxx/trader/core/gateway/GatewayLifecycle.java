package org.jdkxx.trader.core.gateway;

import org.jdkxx.trader.gateway.BrokerGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Connects the enabled, auto-connect gateways when the application starts (asynchronously, so an
 * unreachable gateway does not hold up startup) and disconnects them when it stops.
 */
@Component
public class GatewayLifecycle implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(GatewayLifecycle.class);

  private final GatewayRegistry registry;
  private final GatewayEventRecorder recorder;
  private volatile boolean running;

  public GatewayLifecycle(GatewayRegistry registry, GatewayEventRecorder recorder) {
    this.registry = registry;
    this.recorder = recorder;
  }

  @Override
  public void start() {
    for (BrokerGateway g : registry.all()) {
      g.addListener(recorder);
      if (!g.enabled()) {
        log.info("{} 网关未启用", g.broker());
        continue;
      }
      if (g.autoConnect()) {
        log.info("{} 网关：启动时自动连接", g.broker());
        g.connect();
      } else {
        log.info("{} 网关已启用但 auto-connect=false，等待手工连接", g.broker());
      }
    }
    running = true;
  }

  @Override
  public void stop() {
    for (BrokerGateway g : registry.all()) {
      try {
        g.disconnect();
      } catch (RuntimeException e) {
        log.warn("关闭 {} 网关出错：{}", g.broker(), e.toString());
      }
    }
    // The disconnect event is notified asynchronously and stored asynchronously; the data source
    // closes right after this, so wait here until those writes are done.
    try {
      Thread.sleep(300);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    recorder.flush(java.time.Duration.ofSeconds(3));
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    // Start after infrastructure such as the data source, stop before it.
    return Integer.MAX_VALUE - 1000;
  }
}
