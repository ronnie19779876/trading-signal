package org.jdkxx.trader.core.account;

import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 盈透连上后同步一次持仓（周一重新登录后立刻把池里的 HOLDING 对齐）。只在开了跑批的实例装配。
 *
 * <p>回调在网关调度线程上，不得阻塞：延后到自己的线程执行。延后 60 秒是给富途留出连上的时间——
 * 库里没有的持仓要向富途解析建档。失败只记日志，下一次快照或重连再试。
 */
public class HoldingSyncOnConnect implements GatewayListener, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HoldingSyncOnConnect.class);
    static final Duration DELAY = Duration.ofSeconds(60);

    private final HoldingSyncService sync;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
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
