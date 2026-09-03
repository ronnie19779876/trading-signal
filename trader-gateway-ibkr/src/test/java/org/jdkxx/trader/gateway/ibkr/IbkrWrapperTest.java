package org.jdkxx.trader.gateway.ibkr;

import com.ib.client.ContractDetails;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class IbkrWrapperTest {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final List<String> events = new ArrayList<>();
    private final IbkrRequestRegistry registry = new IbkrRequestRegistry(scheduler, Runnable::run, Duration.ofSeconds(5));
    private final IbkrWrapper wrapper = new IbkrWrapper(new IbkrWrapper.ConnectionEvents() {
        @Override
        public void onConnectAck() {
            events.add("ack");
        }

        @Override
        public void onNextValidId(int orderId) {
            events.add("nextValidId=" + orderId);
        }

        @Override
        public void onManagedAccounts(String accounts) {
            events.add("accounts");
        }

        @Override
        public void onCurrentTime(long epochSeconds) {
            events.add("time=" + epochSeconds);
        }

        @Override
        public void onConnectionClosed() {
            events.add("closed");
        }

        @Override
        public void onSystemMessage(int code, String message) {
            events.add("sys=" + code);
        }
    }, registry);

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void 连接级回调转给连接对象() {
        wrapper.connectAck();
        wrapper.nextValidId(7);
        wrapper.currentTime(100);
        wrapper.error(-1, 0, 2104, "Market data farm connection is OK:usfarm", null);
        wrapper.connectionClosed();

        assertThat(events).containsExactly("ack", "nextValidId=7", "time=100", "sys=2104", "closed");
    }

    @Test
    void 带reqId的回调与错误交给注册表() {
        int id = registry.nextId();
        CompletableFuture<List<ContractDetails>> f = registry.open(id, "t", new PendingRequest.Many<>(ContractDetails.class), null);
        wrapper.contractDetails(id, new ContractDetails());
        wrapper.contractDetailsEnd(id);
        assertThat(f).isCompleted();
        assertThat(f.join()).hasSize(1);

        int id2 = registry.nextId();
        CompletableFuture<List<ContractDetails>> g = registry.open(id2, "t", new PendingRequest.Many<>(ContractDetails.class), null);
        wrapper.error(id2, 0, 200, "No security definition has been found for the request", null);
        assertThat(g).isCompletedExceptionally();
        assertThat(g.handle((v, ex) -> {
            Throwable c = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
            return c instanceof RequestRejectedException r && r.code() == 200 ? "200" : "?";
        }).join()).isEqualTo("200");
        assertThat(events).doesNotContain("sys=200");
    }
}
