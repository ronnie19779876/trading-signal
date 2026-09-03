package org.jdkxx.trader.core.gateway;

import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.GatewayListener;
import org.jdkxx.trader.storage.gateway.GatewayEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把网关连接事件写日志并落 gateway_event 表。监听器回调在网关的调度线程上，落库转到自己的线程。
 */
@Component
public class GatewayEventRecorder implements GatewayListener {

    private static final Logger log = LoggerFactory.getLogger(GatewayEventRecorder.class);

    private final ObjectProvider<GatewayEventRepository> repository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
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
        executor.execute(() -> {
            try {
                repo.insert(broker, event, detail, at);
            } catch (RuntimeException e) {
                log.warn("写 gateway_event 失败：{}", e.toString());
            }
        });
    }

    /** 等待已提交的落库任务完成（应用关闭时先断网关再关数据源，最后一条 DISCONNECTED 不能丢）。 */
    public void flush(java.time.Duration timeout) {
        try {
            executor.submit(() -> { }).get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("等待网关事件落库超时或失败：{}", e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
