package org.jdkxx.trader.core.gateway;

import org.jdkxx.trader.gateway.BrokerGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 随应用启动连接已启用且 auto-connect 的网关（异步，网关不可达不影响应用启动），随应用关闭断开。
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
        // 断开事件是异步通知 + 异步落库；数据源随后就会关闭，这里等它们写完
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
        // 晚于数据源等基础设施启动、早于它们关闭
        return Integer.MAX_VALUE - 1000;
    }
}
